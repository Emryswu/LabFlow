package com.arthur.labops.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.arthur.labops.common.BusinessException;

/** Real Redis Lua execution through two independent client connections.
 * Explicit opt-in keeps the default offline build independent of Redis.
 */
@EnabledIfEnvironmentVariable(named = "LABFLOW_TEST_REDIS", matches = "true")
@Timeout(30)
class RedisLoginAttemptGuardIntegrationTest {

    private LettuceConnectionFactory factoryA;
    private LettuceConnectionFactory factoryB;
    private StringRedisTemplate redisA;
    private RedisLoginAttemptGuard a;
    private RedisLoginAttemptGuard b;
    private String ip;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    @BeforeEach
    void connect() {
        factoryA = connectionFactory();
        factoryB = connectionFactory();
        redisA = new StringRedisTemplate(factoryA);
        a = new RedisLoginAttemptGuard(redisA, 5, 8, WINDOW);
        b = new RedisLoginAttemptGuard(new StringRedisTemplate(factoryB), 5, 8, WINDOW);
        ip = "integration-" + UUID.randomUUID();
    }

    @AfterEach
    void disconnect() {
        if (factoryB != null) factoryB.destroy();
        if (factoryA != null) factoryA.destroy();
        // Unique test keys expire automatically; never flush a shared Redis.
    }

    @Test
    void concurrentSameIdentityAdmissionsAreBoundedAcrossTwoClients() throws Exception {
        assertThat(race(i -> "student")).hasSize(5).doesNotHaveDuplicates();
        assertThat(redisA.opsForZSet().zCard(RedisLoginAttemptGuard.redisIdentityKey(ip, "student")))
                .isEqualTo(5L);
        assertThat(redisA.opsForZSet().zCard(RedisLoginAttemptGuard.redisIpKey(ip)))
                .isEqualTo(5L);
        assertLimited(b, "student");
    }

    @Test
    void rotatingIdentitiesCannotBypassSharedIpLimit() throws Exception {
        assertThat(race(i -> "student-" + i)).hasSize(8).doesNotHaveDuplicates();
        assertThat(redisA.opsForZSet().zCard(RedisLoginAttemptGuard.redisIpKey(ip)))
                .isEqualTo(8L);
        assertLimited(a, "new-user");
        assertThat(redisA.hasKey(RedisLoginAttemptGuard.redisIdentityKey(ip, "new-user")))
                .isFalse();
    }

    @Test
    void successPreservesOtherPendingAttemptsAndClearsOnlySettledFailures() {
        String identityKey = RedisLoginAttemptGuard.redisIdentityKey(ip, "student");
        String ipKey = RedisLoginAttemptGuard.redisIpKey(ip);
        // Include a member written by the old version for upgrade compatibility.
        double now = System.currentTimeMillis() - 1_000;
        redisA.opsForZSet().add(identityKey, "legacy-failure", now);
        redisA.opsForZSet().add(ipKey, "legacy-failure", now);
        String first = a.beginAttempt(ip, "student");
        String second = b.beginAttempt(ip, "student");
        String third = b.beginAttempt(ip, "student");
        b.recordFailure(ip, "student", third);
        a.recordSuccess(ip, "student", first);
        assertThat(redisA.opsForZSet().range(identityKey, 0, -1))
                .containsExactly("p:" + second);
        assertThat(redisA.opsForZSet().range(ipKey, 0, -1))
                .containsExactlyInAnyOrder("p:" + second, "f:" + third);
        b.recordFailure(ip, "student", second);
        // Replaying completion must not duplicate or refund somebody else's hit.
        b.recordFailure(ip, "student", second);
        a.recordSuccess(ip, "student", first);
        assertThat(redisA.opsForZSet().range(identityKey, 0, -1))
                .containsExactly("f:" + second);
        assertThat(redisA.getExpire(identityKey, TimeUnit.MILLISECONDS)).isPositive();
        assertThat(redisA.getExpire(ipKey, TimeUnit.MILLISECONDS)).isPositive();
    }

    private List<String> race(IntFunction<String> usernames) throws Exception {
        int requests = 24;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                int n = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    try {
                        return (n % 2 == 0 ? a : b).beginAttempt(ip, usernames.apply(n));
                    } catch (BusinessException ex) {
                        assertThat(ex.getCode()).isEqualTo("LOGIN_RATE_LIMITED");
                        return null;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> accepted = new ArrayList<>();
            for (Future<String> future : futures) {
                String attempt = future.get(10, TimeUnit.SECONDS);
                if (attempt != null) accepted.add(attempt);
            }
            return accepted;
        }
    }

    private void assertLimited(RedisLoginAttemptGuard guard, String username) {
        assertThatThrownBy(() -> guard.beginAttempt(ip, username))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("LOGIN_RATE_LIMITED");
    }

    private static LettuceConnectionFactory connectionFactory() {
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 6379));
        factory.afterPropertiesSet();
        return factory;
    }
}
