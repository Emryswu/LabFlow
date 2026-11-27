package com.arthur.labops.auth;

/**
 * Login brute-force throttle abstraction supporting both local in-memory
 * sliding windows and distributed Redis Lua sliding windows.
 */
public interface LoginAttemptThrottle {

    /** Atomically checks both windows and reserves an in-flight attempt. */
    String beginAttempt(String clientIp, String username);

    void recordFailure(String clientIp, String username, String attempt);

    void recordSuccess(String clientIp, String username, String attempt);
}
