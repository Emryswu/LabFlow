package com.arthur.labops.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;

import com.arthur.labops.common.BusinessException;

class RedisLoginAttemptGuardTest {

    @Test
    void identityLimitBlocksNormalizedUsernameWithoutAffectingOtherIps() {
        InMemoryRedisCommands commands = new InMemoryRedisCommands();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-26T10:00:00Z"));
        RedisLoginAttemptGuard guard = new RedisLoginAttemptGuard(
                commands, 3, 20, Duration.ofMinutes(15), clock);

        for (int i = 0; i < 3; i++) {
            fail(guard, "203.0.113.10", "teacher");
            clock.advanceMillis(1);
        }

        assertRateLimited(guard, "203.0.113.10", "TEACHER");
        assertRateLimited(guard, "203.0.113.10", " teacher ");
        assertThatCode(() -> guard.beginAttempt("198.51.100.99", "teacher"))
                .doesNotThrowAnyException();
    }

    @Test
    void ipLimitBlocksUsernameRotationAndNeverCreatesEmptyKeys() {
        InMemoryRedisCommands commands = new InMemoryRedisCommands();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-26T10:00:00Z"));
        RedisLoginAttemptGuard guard = new RedisLoginAttemptGuard(
                commands, 5, 3, Duration.ofMinutes(15), clock);

        for (int i = 0; i < 3; i++) {
            fail(guard, "203.0.113.20", "user-" + i);
        }
        int keyCountBeforeRotation = commands.zsets.size();
        assertThat(keyCountBeforeRotation).isEqualTo(4);

        for (int i = 0; i < 20; i++) {
            assertRateLimited(guard, "203.0.113.20", "rotate-" + i);
        }
        assertThat(commands.zsets).hasSize(keyCountBeforeRotation);
    }

    @Test
    void slidingWindowRecoversAfterWindowElapsesAndSetsPexpire() {
        InMemoryRedisCommands commands = new InMemoryRedisCommands();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-26T10:00:00Z"));
        Duration window = Duration.ofSeconds(60);
        RedisLoginAttemptGuard guard = new RedisLoginAttemptGuard(commands, 2, 2, window, clock);

        fail(guard, "203.0.113.30", "alice");
        clock.advanceMillis(10_000);
        fail(guard, "203.0.113.30", "bob");
        assertRateLimited(guard, "203.0.113.30", "charlie");

        assertThat(commands.ttlsMs.get(RedisLoginAttemptGuard.redisIpKey("203.0.113.30")))
                .isEqualTo(60_000L);

        // Advance past the first failure (at t=0) but keep second (at t=10s) inside window.
        clock.advanceMillis(50_001);
        assertThatCode(() -> guard.beginAttempt("203.0.113.30", "charlie"))
                .doesNotThrowAnyException();
    }

    @Test
    void successfulLoginDeletesIdentityBucketAndDecaysIpBucket() {
        InMemoryRedisCommands commands = new InMemoryRedisCommands();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-26T10:00:00Z"));
        RedisLoginAttemptGuard guard = new RedisLoginAttemptGuard(
                commands, 3, 4, Duration.ofMinutes(15), clock);

        fail(guard, "203.0.113.40", "student");
        clock.advanceMillis(5);
        fail(guard, "203.0.113.40", "student");
        clock.advanceMillis(5);
        fail(guard, "203.0.113.40", "other");
        clock.advanceMillis(5);

        String successful = guard.beginAttempt("203.0.113.40", "student");
        guard.recordSuccess("203.0.113.40", "student", successful);

        assertThat(commands.zsets).doesNotContainKey(
                RedisLoginAttemptGuard.redisIdentityKey("203.0.113.40", "student"));
        assertThat(commands.zsets.get(RedisLoginAttemptGuard.redisIpKey("203.0.113.40")))
                .hasSize(2);
    }

    @Test
    void concurrentFailuresInSameMillisecondRemainAtomicAndNeverOverwriteMembers() throws Exception {
        InMemoryRedisCommands commands = new InMemoryRedisCommands();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-26T10:00:00Z"));
        RedisLoginAttemptGuard guard = new RedisLoginAttemptGuard(
                commands, 10, 10, Duration.ofMinutes(15), clock);

        int threads = 10;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        fail(guard, "203.0.113.50", "concurrent-user");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(commands.zsets.get(RedisLoginAttemptGuard.redisIpKey("203.0.113.50"))).hasSize(10);
        assertThat(commands.zsets.get(RedisLoginAttemptGuard.redisIdentityKey("203.0.113.50", "concurrent-user")))
                .hasSize(10);
        assertRateLimited(guard, "203.0.113.50", "concurrent-user");
    }

    @Test
    void assertAllowedFailsClosedOnRedisOutageWhilePostAuthHooksDoNotMaskOutcome() {
        RedisLoginAttemptGuard.Commands failingConn = new FailingCommands(
                new RedisConnectionFailureException("connection refused"));
        RedisLoginAttemptGuard connGuard = new RedisLoginAttemptGuard(
                failingConn, 5, 20, Duration.ofMinutes(15), Clock.systemUTC());

        assertThatThrownBy(() -> connGuard.beginAttempt("203.0.113.60", "student"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo("REDIS_UNAVAILABLE");
                    assertThat(business.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });

        RedisLoginAttemptGuard.Commands failingOp = new FailingCommands(
                new QueryTimeoutException("redis timeout"));
        RedisLoginAttemptGuard opGuard = new RedisLoginAttemptGuard(
                failingOp, 5, 20, Duration.ofMinutes(15), Clock.systemUTC());

        assertThatThrownBy(() -> opGuard.beginAttempt("203.0.113.60", "student"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo("REDIS_OPERATION_FAILED");
                    assertThat(business.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });

        assertThatCode(() -> opGuard.recordFailure("203.0.113.60", "student", "test")).doesNotThrowAnyException();
        assertThatCode(() -> opGuard.recordSuccess("203.0.113.60", "student", "test")).doesNotThrowAnyException();
    }

    @Test
    void luaScriptsContainAtomicSlidingWindowCommands() {
        assertThat(RedisLoginAttemptGuard.ASSERT_ALLOWED_SCRIPT.getScriptAsString())
                .contains("ZREMRANGEBYSCORE")
                .contains("ZCARD").contains("ZADD");
        assertThat(RedisLoginAttemptGuard.RECORD_FAILURE_SCRIPT.getScriptAsString())
                .contains("ZREMRANGEBYSCORE")
                .contains("ZADD")
                .contains("PEXPIRE");
        assertThat(RedisLoginAttemptGuard.RECORD_SUCCESS_SCRIPT.getScriptAsString())
                .contains("ZREM")
                .contains("ZREMRANGEBYSCORE")
                .contains("ZRANGE");
    }

    @Test
    void concurrentAdmissionCountsInFlightAcrossInstancesAndSuccessDoesNotEraseOthers() {
        InMemoryRedisCommands commands = new InMemoryRedisCommands();
        Clock clock = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC);
        RedisLoginAttemptGuard a = new RedisLoginAttemptGuard(commands, 2, 3, Duration.ofMinutes(15), clock);
        RedisLoginAttemptGuard b = new RedisLoginAttemptGuard(commands, 2, 3, Duration.ofMinutes(15), clock);
        String first = a.beginAttempt("203.0.113.10", "student");
        String second = b.beginAttempt("203.0.113.10", "student");
        assertRateLimited(a, "203.0.113.10", "student");
        a.recordSuccess("203.0.113.10", "student", first);
        String third = a.beginAttempt("203.0.113.10", "student");
        assertRateLimited(b, "203.0.113.10", "student");
        b.recordFailure("203.0.113.10", "student", second);
        a.recordFailure("203.0.113.10", "student", third);
        assertRateLimited(b, "203.0.113.10", "student");
    }

    @Test
    void handlesNullUsernameAndRejectsInvalidConstructorConfig() {
        InMemoryRedisCommands commands = new InMemoryRedisCommands();
        Clock clock = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC);
        RedisLoginAttemptGuard guard = new RedisLoginAttemptGuard(commands, 2, 5, Duration.ofMinutes(1), clock);
        assertThatCode(() -> fail(guard, null, null)).doesNotThrowAnyException();

        assertThatThrownBy(() -> new RedisLoginAttemptGuard(commands, 0, 5, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisLoginAttemptGuard(commands, 2, 0, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisLoginAttemptGuard(commands, 2, 5, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisLoginAttemptGuard(commands, 2, 5, Duration.ofMinutes(1), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RedisLoginAttemptGuard((RedisLoginAttemptGuard.Commands) null, 2, 5, Duration.ofMinutes(1), clock))
                .isInstanceOf(NullPointerException.class);
    }

    private static void fail(LoginAttemptThrottle guard, String ip, String username) {
        String attempt = guard.beginAttempt(ip, username);
        guard.recordFailure(ip, username, attempt);
    }

    private static void assertRateLimited(LoginAttemptThrottle guard, String ip, String username) {
        assertThatThrownBy(() -> guard.beginAttempt(ip, username))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("LOGIN_RATE_LIMITED");
    }

    private static final class InMemoryRedisCommands implements RedisLoginAttemptGuard.Commands {
        private final Map<String, Map<String, Long>> zsets = new ConcurrentHashMap<>();
        private final Map<String, Long> ttlsMs = new ConcurrentHashMap<>();

        @Override
        public synchronized Long checkAllowed(String ipKey, String identityKey, long cutoffMs, int maxPerIp,
                                              int maxPerIdentity, long nowMs, String member, long windowMs) {
            prune(ipKey, cutoffMs);
            if (size(ipKey) >= maxPerIp) {
                return 1L;
            }
            prune(identityKey, cutoffMs);
            if (size(identityKey) >= maxPerIdentity) {
                return 2L;
            }
            add(ipKey, cutoffMs, nowMs, "p:" + member, windowMs);
            add(identityKey, cutoffMs, nowMs, "p:" + member, windowMs);
            return 0L;
        }

        @Override
        public synchronized void recordFailure(String ipKey, String identityKey, long cutoffMs, long nowMs,
                                               String member, long windowMs) {
            settleFailure(ipKey, cutoffMs, nowMs, member, windowMs);
            settleFailure(identityKey, cutoffMs, nowMs, member, windowMs);
        }

        @Override
        public synchronized void recordSuccess(String ipKey, String identityKey, long cutoffMs, String member) {
            prune(ipKey, cutoffMs);
            prune(identityKey, cutoffMs);
            Map<String, Long> identity = zsets.get(identityKey);
            if (identity == null || identity.remove("p:" + member) == null) return;
            identity.keySet().removeIf(key -> !key.startsWith("p:"));
            Map<String, Long> ip = zsets.get(ipKey);
            if (ip != null) {
                ip.remove("p:" + member);
                ip.entrySet().stream().filter(entry -> !entry.getKey().startsWith("p:"))
                        .min(Comparator.comparingLong(Map.Entry::getValue))
                        .map(Map.Entry::getKey).ifPresent(ip::remove);
            }
            prune(ipKey, cutoffMs);
            prune(identityKey, cutoffMs);
        }

        private void settleFailure(String key, long cutoff, long now, String member, long ttl) {
            prune(key, cutoff);
            Map<String, Long> set = zsets.get(key);
            if (set != null && set.remove("p:" + member) != null) {
                add(key, cutoff, now, "f:" + member, ttl);
            }
        }

        private void add(String key, long cutoffMs, long nowMs, String member, long windowMs) {
            prune(key, cutoffMs);
            zsets.computeIfAbsent(key, ignored -> new HashMap<>()).put(member, nowMs);
            ttlsMs.put(key, windowMs);
        }

        private void prune(String key, long cutoffMs) {
            Map<String, Long> set = zsets.get(key);
            if (set == null) {
                return;
            }
            set.entrySet().removeIf(entry -> entry.getValue() < cutoffMs);
            if (set.isEmpty()) {
                zsets.remove(key);
            }
        }

        private int size(String key) {
            Map<String, Long> set = zsets.get(key);
            return set == null ? 0 : set.size();
        }
    }

    private static final class FailingCommands implements RedisLoginAttemptGuard.Commands {
        private final RuntimeException error;

        private FailingCommands(RuntimeException error) {
            this.error = error;
        }

        @Override
        public Long checkAllowed(String ipKey, String identityKey, long cutoffMs, int maxPerIp, int maxPerIdentity,
                                 long nowMs, String member, long windowMs) {
            throw error;
        }

        @Override
        public void recordFailure(String ipKey, String identityKey, long cutoffMs, long nowMs, String member,
                                  long windowMs) {
            throw error;
        }

        @Override
        public void recordSuccess(String ipKey, String identityKey, long cutoffMs, String member) {
            throw error;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            this.current = initial;
        }

        void advanceMillis(long millis) {
            current = current.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
