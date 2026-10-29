package com.blog.auth;

import com.blog.common.ApiException;
import com.blog.shiro.JedisRedisManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisAuthTokenStoreTest {
    @Test
    void tokenKeysAreHashedNamespacedAndExpireWithTheJwt() {
        JedisRedisManager redis = mock(JedisRedisManager.class);
        RedisAuthTokenStore store = new RedisAuthTokenStore(redis);
        store.allow("private-token", 7, 120);
        verify(redis).set(argThat((byte[] key) -> {
            String value = new String(key, StandardCharsets.UTF_8);
            return value.matches("marker-hub:auth:token:[0-9a-f]{64}") && !value.contains("private-token");
        }), argThat((byte[] value) -> java.util.Arrays.equals(value, "7".getBytes(StandardCharsets.UTF_8))), eq(120));
        store.revoke("private-token");
        verify(redis).del(argThat((byte[] key) -> new String(key, StandardCharsets.UTF_8).startsWith("marker-hub:auth:token:")));
    }

    @Test
    void rateLimitUsesAtomicExpiringCountersWithinTheProjectNamespace() {
        JedisRedisManager redis = mock(JedisRedisManager.class);
        RedisAuthTokenStore store = new RedisAuthTokenStore(redis);
        when(redis.incrementWindow(any(), eq(60))).thenReturn(1L, 1L, 31L, 2L);
        assertTrue(store.allowLoginAttempt("admin", "127.0.0.1"));
        assertFalse(store.allowLoginAttempt("admin", "127.0.0.1"));
        verify(redis, times(4)).incrementWindow(argThat((byte[] key) ->
                new String(key, StandardCharsets.UTF_8).startsWith("marker-hub:login:")), eq(60));
    }

    @Test
    void redisFailureCannotAuthorizeARequest() {
        JedisRedisManager redis = mock(JedisRedisManager.class);
        RedisAuthTokenStore store = new RedisAuthTokenStore(redis);
        when(redis.get(any())).thenThrow(new JedisConnectionException("unavailable"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ApiException.class, () -> store.contains("token", 1)).getStatus());
    }
}
