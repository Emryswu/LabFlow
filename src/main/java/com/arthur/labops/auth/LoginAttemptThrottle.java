package com.arthur.labops.auth;

/**
 * Login brute-force throttle abstraction supporting both local in-memory
 * sliding windows and distributed Redis Lua sliding windows.
 */
public interface LoginAttemptThrottle {

    void assertAllowed(String clientIp, String username);

    void recordFailure(String clientIp, String username);

    void recordSuccess(String clientIp, String username);
}
