package com.arthur.labops.auth;

import java.time.Duration;
import java.util.Locale;

import org.springframework.http.HttpStatus;

import com.arthur.labops.common.BusinessException;

/**
 * Shared key normalization, config validation, and rate-limit errors for both
 * local and Redis login throttles.
 */
final class LoginThrottleKeys {

    private LoginThrottleKeys() {
    }

    static String ipKey(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "ip:unknown";
        }
        return "ip:" + clientIp.trim();
    }

    static String identityKey(String clientIp, String username) {
        String normalizedUser = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return ipKey(clientIp) + "|" + normalizedUser;
    }

    static void validateConfig(int maxPerIdentity, int maxPerIp, Duration window) {
        if (maxPerIdentity <= 0) {
            throw new IllegalArgumentException("maxPerIdentity must be positive");
        }
        if (maxPerIp <= 0) {
            throw new IllegalArgumentException("maxPerIp must be positive");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
    }

    static BusinessException rateLimited() {
        return new BusinessException(
                "LOGIN_RATE_LIMITED",
                "登录尝试过于频繁，请稍后重试",
                HttpStatus.TOO_MANY_REQUESTS);
    }
}
