package com.arthur.labops.auth;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.arthur.labops.common.BusinessException;

/** Both buckets are admitted and settled under one monitor; password verification
 * itself runs outside the monitor. Pending attempts count against the same limit
 * as failed attempts, and a successful login cannot erase another pending attempt.
 */
@Component
@ConditionalOnProperty(name = "labops.login.mode", havingValue = "local", matchIfMissing = true)
public class LocalLoginAttemptGuard implements LoginAttemptThrottle {
    static final int DEFAULT_MAX_BUCKETS = 10_000;

    private final int maxPerIdentity;
    private final int maxPerIp;
    private final long windowMs;
    private final int maxBuckets;
    private final Map<String, AttemptWindow> windows = new HashMap<>();

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
    public synchronized String beginAttempt(String clientIp, String username) {
        long now = System.currentTimeMillis();
        String ip = ipKey(clientIp);
        String identity = identityKey(clientIp, username);
        AttemptWindow ipWindow = prune(ip, now);
        AttemptWindow idWindow = prune(identity, now);
        if (count(ipWindow) >= maxPerIp || count(idWindow) >= maxPerIdentity) {
            throw limited();
        }
        int required = (ipWindow == null ? 1 : 0) + (idWindow == null ? 1 : 0);
        if (windows.size() + required > maxBuckets) {
            evictExpiredWindows(now);
            if (windows.size() + required > maxBuckets) {
                // Never evict a live failure or in-flight reservation to admit an attacker.
                throw limited();
            }
        }
        String attempt = UUID.randomUUID().toString();
        Hit hit = new Hit(now, true);
        windows.computeIfAbsent(ip, key -> new AttemptWindow()).hits.put(attempt, hit);
        windows.computeIfAbsent(identity, key -> new AttemptWindow()).hits.put(attempt, hit);
        return attempt;
    }

    @Override
    public synchronized void recordFailure(String clientIp, String username, String attempt) {
        long now = System.currentTimeMillis();
        fail(prune(ipKey(clientIp), now), attempt, now);
        fail(prune(identityKey(clientIp, username), now), attempt, now);
    }

    private static void fail(AttemptWindow window, String attempt, long now) {
        if (window == null) return;
        Hit hit = window.hits.get(attempt);
        if (hit != null && hit.pending()) {
            window.hits.remove(attempt);
            window.hits.put(attempt, new Hit(now, false));
        }
    }

    @Override
    public synchronized void recordSuccess(String clientIp, String username, String attempt) {
        long now = System.currentTimeMillis();
        String ip = ipKey(clientIp);
        String identity = identityKey(clientIp, username);
        AttemptWindow idWindow = prune(identity, now);
        AttemptWindow ipWindow = prune(ip, now);
        if (idWindow == null) return;
        Hit hit = idWindow.hits.get(attempt);
        if (hit == null || !hit.pending()) return;
        idWindow.hits.remove(attempt);
        idWindow.hits.values().removeIf(value -> !value.pending());
        if (ipWindow != null) {
            ipWindow.hits.remove(attempt);
            // Preserve the existing success decay of one oldest failed IP attempt.
            var iterator = ipWindow.hits.values().iterator();
            while (iterator.hasNext()) {
                if (!iterator.next().pending()) {
                    iterator.remove();
                    break;
                }
            }
        }
        prune(identity, now);
        prune(ip, now);
    }

    synchronized int windowCount() {
        return windows.size();
    }

    private AttemptWindow prune(String key, long now) {
        AttemptWindow window = windows.get(key);
        if (window != null) {
            window.hits.values().removeIf(hit -> now - hit.at() > windowMs);
            if (window.hits.isEmpty()) {
                windows.remove(key);
                return null;
            }
        }
        return window;
    }

    private static int count(AttemptWindow window) {
        return window == null ? 0 : window.hits.size();
    }

    private static BusinessException limited() {
        return new BusinessException("LOGIN_RATE_LIMITED", "登录尝试过于频繁，请稍后重试",
                HttpStatus.TOO_MANY_REQUESTS);
    }

    @Scheduled(fixedDelayString = "${labops.login.evict-interval:60000}")
    synchronized void evictExpiredWindows() {
        evictExpiredWindows(System.currentTimeMillis());
    }

    private void evictExpiredWindows(long now) {
        var iterator = windows.values().iterator();
        while (iterator.hasNext()) {
            AttemptWindow window = iterator.next();
            window.hits.values().removeIf(hit -> now - hit.at() > windowMs);
            if (window.hits.isEmpty()) iterator.remove();
        }
    }

    static String identityKey(String clientIp, String username) {
        return LoginThrottleKeys.identityKey(clientIp, username);
    }

    static String ipKey(String clientIp) {
        return LoginThrottleKeys.ipKey(clientIp);
    }

    private record Hit(long at, boolean pending) {}
    private static final class AttemptWindow {
        final Map<String, Hit> hits = new LinkedHashMap<>();
    }
}
