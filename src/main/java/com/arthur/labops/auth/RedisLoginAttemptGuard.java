package com.arthur.labops.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

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

/**
 * Distributed Redis sliding-window login throttle ({@code labops.login.mode=redis}).
 *
 * <p>Uses atomic Lua scripts backed by Redis ZSETs (score = attempt epoch ms,
 * member = unique attempt token) so multiple application instances share one
 * brute-force protection state without TOCTOU races.
 */
@Component
@ConditionalOnProperty(name = "labops.login.mode", havingValue = "redis")
public class RedisLoginAttemptGuard implements LoginAttemptThrottle {

    private static final Logger log = LoggerFactory.getLogger(RedisLoginAttemptGuard.class);
    private static final String KEY_PREFIX = "labops:login:throttle:";

    static final DefaultRedisScript<Long> ASSERT_ALLOWED_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[1]) "
                    + "if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then return 1 end "
                    + "redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', '(' .. ARGV[1]) "
                    + "if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[3]) then return 2 end "
                    + "return 0",
            Long.class);

    static final DefaultRedisScript<Long> RECORD_FAILURE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[1]) "
                    + "redis.call('ZADD', KEYS[1], tonumber(ARGV[2]), ARGV[3]) "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[4])) "
                    + "redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', '(' .. ARGV[1]) "
                    + "redis.call('ZADD', KEYS[2], tonumber(ARGV[2]), ARGV[3]) "
                    + "redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[4])) "
                    + "return 1",
            Long.class);

    static final DefaultRedisScript<Long> RECORD_SUCCESS_SCRIPT = new DefaultRedisScript<>(
            "redis.call('DEL', KEYS[2]) "
                    + "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[1]) "
                    + "redis.call('ZPOPMIN', KEYS[1], 1) "
                    + "return 1",
            Long.class);

    interface Commands {
        Long checkAllowed(String ipKey, String identityKey, long cutoffMs, int maxPerIp, int maxPerIdentity);

        void recordFailure(String ipKey, String identityKey, long cutoffMs, long nowMs, String member, long windowMs);

        void recordSuccess(String ipKey, String identityKey, long cutoffMs);
    }

    private final Commands commands;
    private final int maxPerIdentity;
    private final int maxPerIp;
    private final long windowMs;
    private final Clock clock;

    @Autowired
    public RedisLoginAttemptGuard(
            StringRedisTemplate redisTemplate,
            @Value("${labops.login.max-failures-per-identity:5}") int maxPerIdentity,
            @Value("${labops.login.max-failures-per-ip:20}") int maxPerIp,
            @Value("${labops.login.window:15m}") Duration window) {
        this(new TemplateCommands(redisTemplate), maxPerIdentity, maxPerIp, window, Clock.systemUTC());
    }

    RedisLoginAttemptGuard(
            Commands commands,
            int maxPerIdentity,
            int maxPerIp,
            Duration window,
            Clock clock) {
        this.commands = commands;
        this.maxPerIdentity = maxPerIdentity;
        this.maxPerIp = maxPerIp;
        this.windowMs = window.toMillis();
        this.clock = clock;
    }

    @Override
    public void assertAllowed(String clientIp, String username) {
        long now = clock.millis();
        long cutoff = now - windowMs;
        String ipKey = redisIpKey(clientIp);
        String idKey = redisIdentityKey(clientIp, username);
        Long result;
        try {
            result = commands.checkAllowed(ipKey, idKey, cutoff, maxPerIp, maxPerIdentity);
        } catch (RedisConnectionFailureException exception) {
            throw new BusinessException(
                    "REDIS_UNAVAILABLE",
                    "登录限流服务暂时不可用",
                    HttpStatus.SERVICE_UNAVAILABLE);
        } catch (DataAccessException exception) {
            throw new BusinessException(
                    "REDIS_OPERATION_FAILED",
                    "登录限流操作失败",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (result == null || result != 0L) {
            throw new BusinessException(
                    "LOGIN_RATE_LIMITED",
                    "登录尝试过于频繁，请稍后重试",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @Override
    public void recordFailure(String clientIp, String username) {
        long now = clock.millis();
        long cutoff = now - windowMs;
        String member = UUID.randomUUID().toString();
        try {
            commands.recordFailure(
                    redisIpKey(clientIp),
                    redisIdentityKey(clientIp, username),
                    cutoff,
                    now,
                    member,
                    windowMs);
        } catch (DataAccessException exception) {
            // Do not mask the 401 authentication failure already produced by AuthService.
            log.warn("Failed to record login failure in Redis for ipKey={}", redisIpKey(clientIp), exception);
        }
    }

    @Override
    public void recordSuccess(String clientIp, String username) {
        long cutoff = clock.millis() - windowMs;
        try {
            commands.recordSuccess(redisIpKey(clientIp), redisIdentityKey(clientIp, username), cutoff);
        } catch (DataAccessException exception) {
            // Do not turn a completed login into 503 if Redis blips post-auth.
            log.warn("Failed to clear login throttle in Redis for ipKey={}", redisIpKey(clientIp), exception);
        }
    }

    static String redisIpKey(String clientIp) {
        return KEY_PREFIX + LoginThrottleKeys.ipKey(clientIp);
    }

    static String redisIdentityKey(String clientIp, String username) {
        return KEY_PREFIX + LoginThrottleKeys.identityKey(clientIp, username);
    }

    private static final class TemplateCommands implements Commands {
        private final StringRedisTemplate redisTemplate;

        private TemplateCommands(StringRedisTemplate redisTemplate) {
            this.redisTemplate = redisTemplate;
        }

        @Override
        public Long checkAllowed(String ipKey, String identityKey, long cutoffMs, int maxPerIp, int maxPerIdentity) {
            return redisTemplate.execute(
                    ASSERT_ALLOWED_SCRIPT,
                    List.of(ipKey, identityKey),
                    Long.toString(cutoffMs),
                    Integer.toString(maxPerIp),
                    Integer.toString(maxPerIdentity));
        }

        @Override
        public void recordFailure(String ipKey, String identityKey, long cutoffMs, long nowMs, String member,
                                  long windowMs) {
            redisTemplate.execute(
                    RECORD_FAILURE_SCRIPT,
                    List.of(ipKey, identityKey),
                    Long.toString(cutoffMs),
                    Long.toString(nowMs),
                    member,
                    Long.toString(windowMs));
        }

        @Override
        public void recordSuccess(String ipKey, String identityKey, long cutoffMs) {
            redisTemplate.execute(
                    RECORD_SUCCESS_SCRIPT,
                    List.of(ipKey, identityKey),
                    Long.toString(cutoffMs));
        }
    }
}
