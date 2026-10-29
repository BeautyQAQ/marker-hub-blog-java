package com.blog.auth;

public interface AuthTokenStore {
    void allow(String token, long userId, int ttlSeconds);
    boolean contains(String token, long userId);
    void revoke(String token);
    boolean allowLoginAttempt(String username, String clientIp);
}
