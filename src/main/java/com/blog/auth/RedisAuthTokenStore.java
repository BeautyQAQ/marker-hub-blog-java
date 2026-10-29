package com.blog.auth;

import com.blog.common.ApiException;
import com.blog.shiro.JedisRedisManager;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import redis.clients.jedis.exceptions.JedisException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Supplier;

@Component
@Profile("!bootstrap-admin")
public class RedisAuthTokenStore implements AuthTokenStore {
    private final JedisRedisManager redis;

    public RedisAuthTokenStore(JedisRedisManager redis) {
        this.redis = redis;
    }

    @Override
    public void allow(String token, long userId, int ttlSeconds) {
        execute(() -> redis.set(key(token), bytes(Long.toString(userId)), ttlSeconds));
    }

    @Override
    public boolean contains(String token, long userId) {
        return execute(() -> Long.toString(userId).equals(value(redis.get(key(token)))));
    }

    @Override
    public void revoke(String token) {
        execute(() -> { redis.del(key(token)); return null; });
    }

    @Override
    public boolean allowLoginAttempt(String username, String clientIp) {
        return execute(() -> {
            long ipAttempts = redis.incrementWindow(bytes("marker-hub:login:ip:" + hash(clientIp)), 60);
            long userAttempts = redis.incrementWindow(bytes("marker-hub:login:user:" + hash(username.toLowerCase(Locale.ROOT))), 60);
            return ipAttempts <= 30 && userAttempts <= 10;
        });
    }

    private byte[] key(String token) {
        return bytes("marker-hub:auth:token:" + hash(token));
    }

    private String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(input)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private String value(byte[] value) { return value == null ? null : new String(value, StandardCharsets.UTF_8); }

    private <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (JedisException unavailable) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "认证服务暂时不可用");
        }
    }
}
