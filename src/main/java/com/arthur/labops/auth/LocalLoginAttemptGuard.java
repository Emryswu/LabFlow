package com.arthur.labops.auth;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.arthur.labops.common.BusinessException;

/**
 * In-memory login throttle ({@code labops.login.mode=local}). Keys are IP+username
 * and IP, so one client cannot lock a victim on another IP, and rotating usernames
 * still hits the IP cap.
 */
@Component
@ConditionalOnProperty(name = "labops.login.mode", havingValue = "local", matchIfMissing = true)
public class LocalLoginAttemptGuard implements LoginAttemptThrottle {

    static final int DEFAULT_MAX_BUCKETS = 10_000;

    private final int maxPerIdentity;
    private final int maxPerIp;
    private final long windowMs;
    private final int maxBuckets;
    private final Object evictionLock = new Object();
    private final ConcurrentHashMap<String, AttemptWindow> windows = new ConcurrentHashMap<>();

    @Autowired
    public LocalLoginAttemptGuard(
            @Value("${labops.login.max-failures-per-identity:5}") int maxPerIdentity,
            @Value("${labops.login.max-failures-per-ip:20}") int maxPerIp,
            @Value("${labops.login.window:15m}") Duration window) {
        this(maxPerIdentity, maxPerIp, window, DEFAULT_MAX_BUCKETS);
    }

    LocalLoginAttemptGuard(int maxPerIdentity, int maxPerIp, Duration window, int maxBuckets) {
        if (maxBuckets <= 0) {
            throw new IllegalArgumentException("maxBuckets must be positive");
        }
        this.maxPerIdentity = maxPerIdentity;
        this.maxPerIp = maxPerIp;
        this.windowMs = window.toMillis();
        this.maxBuckets = maxBuckets;
    }

    @Override
    public void assertAllowed(String clientIp, String username) {
        long now = System.currentTimeMillis();
        // Only the two buckets this request actually consults are pruned here.
        // Read existing buckets only: creating a per-username key on 429s would let
        // an IP-capped client grow the map by rotating usernames.
        if (snapshotCount(ipKey(clientIp), now) >= maxPerIp
                || snapshotCount(identityKey(clientIp, username), now) >= maxPerIdentity) {
            throw new BusinessException(
                    "LOGIN_RATE_LIMITED",
                    "登录尝试过于频繁，请稍后重试",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @Override
    public void recordFailure(String clientIp, String username) {
        long now = System.currentTimeMillis();
        record(identityKey(clientIp, username), now);
        record(ipKey(clientIp), now);
    }

    @Override
    public void recordSuccess(String clientIp, String username) {
        windows.remove(identityKey(clientIp, username));
        windows.computeIfPresent(ipKey(clientIp), (key, window) -> {
            int remaining = window.decay(System.currentTimeMillis(), windowMs);
            return remaining == 0 ? null : window;
        });
    }

    int windowCount() {
        return windows.size();
    }

    private void record(String key, long now) {
        if (!windows.containsKey(key) && windows.size() >= maxBuckets) {
            evictForCapacity(now);
        }
        windows.compute(key, (ignored, existing) -> {
            AttemptWindow window = existing != null ? existing : new AttemptWindow();
            window.add(now, windowMs);
            return window;
        });
    }

    private int snapshotCount(String key, long now) {
        return snapshotCount(key, now, true);
    }

    private int snapshotCount(String key, long now, boolean touch) {
        int[] count = {0};
        windows.computeIfPresent(key, (ignored, window) -> {
            int remaining = window.pruneAndCount(now, windowMs, touch);
            count[0] = remaining;
            return remaining == 0 ? null : window;
        });
        return count[0];
    }

    /**
     * Drops buckets whose attempts have all aged out. Runs on a timer instead of
     * inside {@link #assertAllowed} so a login check stays O(1) no matter how many
     * buckets a brute-force attempt has created.
     */
    @Scheduled(fixedDelayString = "${labops.login.evict-interval:60000}")
    void evictExpiredWindows() {
        evictExpiredWindows(System.currentTimeMillis());
    }

    private void evictExpiredWindows(long now) {
        for (String key : windows.keySet()) {
            snapshotCount(key, now, false);
        }
    }

    private void evictForCapacity(long now) {
        synchronized (evictionLock) {
            if (windows.size() < maxBuckets) {
                return;
            }
            evictExpiredWindows(now);
            if (windows.size() < maxBuckets) {
                return;
            }
            int toRemove = windows.size() - maxBuckets + 1;
            List<Map.Entry<String, AttemptWindow>> entries = new ArrayList<>(windows.entrySet());
            entries.sort(Comparator.comparingLong(entry -> entry.getValue().lastActivityAt()));
            for (int i = 0; i < entries.size() && toRemove > 0; i++) {
                Map.Entry<String, AttemptWindow> victim = entries.get(i);
                if (windows.remove(victim.getKey(), victim.getValue())) {
                    toRemove--;
                }
            }
        }
    }

    static String identityKey(String clientIp, String username) {
        return LoginThrottleKeys.identityKey(clientIp, username);
    }

    static String ipKey(String clientIp) {
        return LoginThrottleKeys.ipKey(clientIp);
    }

    private static final class AttemptWindow {
        private final ArrayDeque<Long> times = new ArrayDeque<>();
        private volatile long lastActivityAt;

        synchronized int pruneAndCount(long now, long windowMs, boolean touch) {
            while (!times.isEmpty() && now - times.peekFirst() > windowMs) {
                times.pollFirst();
            }
            if (touch && !times.isEmpty() && now > lastActivityAt) {
                lastActivityAt = now;
            }
            return times.size();
        }

        synchronized void add(long now, long windowMs) {
            pruneAndCount(now, windowMs, false);
            times.addLast(now);
            if (now > lastActivityAt) {
                lastActivityAt = now;
            }
        }

        synchronized int decay(long now, long windowMs) {
            pruneAndCount(now, windowMs, false);
            if (!times.isEmpty()) {
                times.pollFirst();
            }
            if (now > lastActivityAt) {
                lastActivityAt = now;
            }
            return times.size();
        }

        long lastActivityAt() {
            return lastActivityAt;
        }
    }
}
