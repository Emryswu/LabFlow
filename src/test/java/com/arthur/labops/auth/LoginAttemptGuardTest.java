package com.arthur.labops.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.arthur.labops.common.BusinessException;

class LoginAttemptGuardTest {

    @Test
    void rotatingUsernamesAfterIpCapDoesNotGrowBuckets() {
        LocalLoginAttemptGuard guard = new LocalLoginAttemptGuard(5, 3, Duration.ofHours(1));
        for (int i = 0; i < 3; i++) {
            fail(guard, "203.0.113.50", "seed-" + i);
        }
        int bucketsAtCap = guard.windowCount();
        assertThat(bucketsAtCap).isEqualTo(4);

        for (int i = 0; i < 200; i++) {
            assertRateLimited(guard, "203.0.113.50", "rotate-" + i);
        }
        assertThat(guard.windowCount()).isEqualTo(bucketsAtCap);
    }

    @Test
    void identityLimitStillRejectsSameUsername() {
        LocalLoginAttemptGuard guard = new LocalLoginAttemptGuard(3, 20, Duration.ofHours(1));
        for (int i = 0; i < 3; i++) {
            fail(guard, "203.0.113.51", "teacher");
        }
        assertRateLimited(guard, "203.0.113.51", "TEACHER");
        assertRateLimited(guard, "203.0.113.51", " teacher ");
    }

    @Test
    void ipLimitStillRejectsOtherUsernames() {
        LocalLoginAttemptGuard guard = new LocalLoginAttemptGuard(5, 3, Duration.ofHours(1));
        for (int i = 0; i < 3; i++) {
            fail(guard, "203.0.113.52", "user-" + i);
        }
        assertRateLimited(guard, "203.0.113.52", "someone-else");
    }

    @Test
    void expiredWindowsAreDroppedAndCountingResumes() throws Exception {
        LocalLoginAttemptGuard guard = new LocalLoginAttemptGuard(2, 2, Duration.ofMillis(80));
        fail(guard, "203.0.113.53", "student");
        fail(guard, "203.0.113.53", "other");
        assertRateLimited(guard, "203.0.113.53", "later");
        assertThat(guard.windowCount()).isGreaterThan(0);

        Thread.sleep(120);

        guard.evictExpiredWindows();
        assertThat(guard.windowCount()).isZero();
        fail(guard, "203.0.113.53", "later");
        assertThat(guard.windowCount()).isEqualTo(2);
    }

    /**
     * A single check must not walk the whole bucket map. Buckets are created by the
     * brute-force traffic this guard defends against, so a per-request sweep made
     * every login slower exactly as an attack ramped up.
     */
    @Test
    void singleCheckDoesNotSweepUnrelatedBuckets() throws Exception {
        LocalLoginAttemptGuard guard = new LocalLoginAttemptGuard(5, 100, Duration.ofMillis(60));
        for (int i = 0; i < 20; i++) {
            fail(guard, "198.51.100." + i, "user-" + i);
        }
        int bucketsBefore = guard.windowCount();
        assertThat(bucketsBefore).isEqualTo(40);

        Thread.sleep(100);

        // Every bucket above is now expired. One unrelated check touches only the
        // two keys it reads, so the other 40 must survive untouched.
        assertThatCode(() -> guard.beginAttempt("203.0.113.99", "someone"))
                .doesNotThrowAnyException();
        assertThat(guard.windowCount()).isEqualTo(bucketsBefore + 2);

        // The scheduled sweep is what reclaims them.
        guard.evictExpiredWindows();
        assertThat(guard.windowCount()).isEqualTo(2);
    }

    @Test
    void successfulLoginRemovesIdentityAndDecaysIp() {
        LocalLoginAttemptGuard guard = new LocalLoginAttemptGuard(3, 20, Duration.ofHours(1));
        fail(guard, "203.0.113.54", "student");
        fail(guard, "203.0.113.54", "student");
        String successful = guard.beginAttempt("203.0.113.54", "student");
        guard.recordSuccess("203.0.113.54", "student", successful);

        fail(guard, "203.0.113.54", "student");
        fail(guard, "203.0.113.54", "student");
        guard.beginAttempt("203.0.113.54", "student");
    }

    @Test
    void capacityNeverEvictsLiveAttemptsAndReclaimsExpiredBuckets() throws Exception {
        LocalLoginAttemptGuard guard = new LocalLoginAttemptGuard(1, 2, Duration.ofHours(1), 4);
        fail(guard, "198.51.100.10", "first");
        fail(guard, "198.51.100.11", "second");
        assertRateLimited(guard, "198.51.100.12", "third");
        assertRateLimited(guard, "198.51.100.10", "first");
        assertThat(guard.windowCount()).isEqualTo(4);

        LocalLoginAttemptGuard expired = new LocalLoginAttemptGuard(1, 2, Duration.ofMillis(60), 4);
        fail(expired, "198.51.100.10", "first");
        fail(expired, "198.51.100.11", "second");
        Thread.sleep(90);
        fail(expired, "198.51.100.12", "third");
        assertThat(expired.windowCount()).isEqualTo(2);
    }

    @Test
    void inFlightAttemptsCountAndSuccessDoesNotEraseAnotherReservation() {
        LocalLoginAttemptGuard guard = new LocalLoginAttemptGuard(2, 3, Duration.ofHours(1));
        String a = guard.beginAttempt("203.0.113.10", "student");
        String b = guard.beginAttempt("203.0.113.10", "student");
        assertRateLimited(guard, "203.0.113.10", "student");
        guard.recordSuccess("203.0.113.10", "student", a);
        String c = guard.beginAttempt("203.0.113.10", "student");
        assertRateLimited(guard, "203.0.113.10", "student");
        guard.recordFailure("203.0.113.10", "student", b);
        guard.recordFailure("203.0.113.10", "student", c);
        assertRateLimited(guard, "203.0.113.10", "student");
    }

    @Test
    void handlesNullUsernameAndRejectsInvalidConstructorConfig() {
        LocalLoginAttemptGuard guard = new LocalLoginAttemptGuard(2, 5, Duration.ofMinutes(1));
        assertThat(LoginThrottleKeys.identityKey(null, null)).isEqualTo("ip:unknown|");
        assertThatCode(() -> fail(guard, null, null)).doesNotThrowAnyException();

        assertThatThrownBy(() -> new LocalLoginAttemptGuard(0, 5, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalLoginAttemptGuard(2, 0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalLoginAttemptGuard(2, 5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalLoginAttemptGuard(2, 5, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalLoginAttemptGuard(2, 5, null))
                .isInstanceOf(IllegalArgumentException.class);
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
}
