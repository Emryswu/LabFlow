package com.arthur.labops.reservation.lock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.arthur.labops.common.BusinessException;

@Component
@ConditionalOnProperty(name = "labops.reservation-lock.mode", havingValue = "redis")
public class RedisReservationLock implements ReservationLock {

    private static final Logger log = LoggerFactory.getLogger(RedisReservationLock.class);
    private static final String EQUIPMENT_PREFIX = "labops:reservation:equipment:";
    private static final long BASE_RETRY_MS = 10L;
    private static final long MAX_BACKOFF_MS = 80L;

    static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    interface Commands {
        Boolean setIfAbsent(String key, String token, Duration leaseTime);

        Long unlock(String key, String token);
    }

    private final Commands commands;
    private final Duration waitTime;
    private final Duration leaseTime;

    @Autowired
    public RedisReservationLock(
            StringRedisTemplate redisTemplate,
            @Value("${labops.reservation-lock.wait:2s}") Duration waitTime,
            @Value("${labops.reservation-lock.lease:10s}") Duration leaseTime) {
        this(new TemplateCommands(redisTemplate), waitTime, leaseTime);
    }

    RedisReservationLock(Commands commands, Duration waitTime, Duration leaseTime) {
        this.commands = commands;
        this.waitTime = waitTime;
        this.leaseTime = leaseTime;
    }

    @Override
    public <T> T execute(Long equipmentId, Supplier<T> action) {
        return executeKeys(List.of(EQUIPMENT_PREFIX + equipmentId), action);
    }

    @Override
    public <T> T executeWindow(Long equipmentId, Instant startTime, Instant endTime, Supplier<T> action) {
        return executeKeys(ReservationLockKeys.forWindow(EQUIPMENT_PREFIX, equipmentId, startTime, endTime), action);
    }

    @Override
    public <T> T executeUser(Long userId, Supplier<T> action) {
        return executeKeys(List.of("labops:reservation:user:" + userId), action);
    }

    private <T> T executeKeys(List<String> keys, Supplier<T> action) {
        long deadline = System.nanoTime() + waitTime.toNanos();
        List<HeldLock> acquired = new ArrayList<>(keys.size());
        try {
            for (String key : keys) {
                String token = UUID.randomUUID().toString();
                if (!tryAcquire(key, token, deadline)) {
                    throw new BusinessException(
                            "RESERVATION_LOCK_TIMEOUT",
                            "预约请求较多，请稍后重试",
                            HttpStatus.CONFLICT);
                }
                acquired.add(new HeldLock(key, token));
                log.info("Redis reservation lock acquired key={}", key);
            }
            // Database/JPA failures from the supplier must not be translated into Redis 503s.
            return action.get();
        } finally {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                HeldLock held = acquired.get(i);
                release(held.key(), held.token());
            }
        }
    }

    private boolean tryAcquire(String key, String token, long deadline) {
        try {
            int attempt = 0;
            while (true) {
                if (Boolean.TRUE.equals(commands.setIfAbsent(key, token, leaseTime))) {
                    return true;
                }
                if (System.nanoTime() >= deadline) {
                    return false;
                }
                if (!pauseWithBackoff(attempt++, deadline)) {
                    return false;
                }
            }
        } catch (RedisConnectionFailureException exception) {
            throw new BusinessException(
                    "REDIS_UNAVAILABLE",
                    "预约锁服务暂时不可用",
                    HttpStatus.SERVICE_UNAVAILABLE);
        } catch (DataAccessException exception) {
            throw new BusinessException(
                    "REDIS_OPERATION_FAILED",
                    "预约锁操作失败",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private void release(String key, String token) {
        try {
            Long result = commands.unlock(key, token);
            if (Long.valueOf(1L).equals(result)) {
                log.info("Redis reservation lock released key={}", key);
            } else {
                log.warn(
                        "Redis reservation lock not released (expired or not owned) key={} result={}",
                        key,
                        result);
            }
        } catch (DataAccessException ignored) {
            // 锁带有租约，释放失败时会自动过期，不能覆盖已经完成的业务结果。
        }
    }

    private boolean pauseWithBackoff(int attempt, long deadline) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remainingMs <= 0L) {
            return false;
        }
        long baseMs = Math.min(MAX_BACKOFF_MS, BASE_RETRY_MS << Math.min(attempt, 3));
        long jitterMs = ThreadLocalRandom.current().nextLong(0L, (baseMs / 2L) + 1L);
        long sleepMs = Math.max(1L, Math.min(baseMs + jitterMs, remainingMs));
        try {
            Thread.sleep(sleepMs);
            return System.nanoTime() < deadline;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(
                    "RESERVATION_INTERRUPTED",
                    "预约请求被中断",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private record HeldLock(String key, String token) {
    }

    private static final class TemplateCommands implements Commands {
        private final StringRedisTemplate redisTemplate;

        private TemplateCommands(StringRedisTemplate redisTemplate) {
            this.redisTemplate = redisTemplate;
        }

        @Override
        public Boolean setIfAbsent(String key, String token, Duration leaseTime) {
            return redisTemplate.opsForValue().setIfAbsent(key, token, leaseTime);
        }

        @Override
        public Long unlock(String key, String token) {
            return redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        }
    }
}
