package com.arthur.labops.auth;

import java.util.Locale;

/**
 * Shared key normalization for both local and Redis login throttles.
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
        return ipKey(clientIp) + "|" + username.trim().toLowerCase(Locale.ROOT);
    }
}
