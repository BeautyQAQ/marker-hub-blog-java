package com.blog.shiro;

import org.crazycake.shiro.IRedisManager;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps Shiro's existing Redis session/cache interfaces on the current Jedis API.
 */
public final class JedisRedisManager implements IRedisManager, AutoCloseable {
    private final RedisClient client;

    public JedisRedisManager(String hostAndPort, String password, int database, int timeoutMillis) {
        DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder()
                .database(database)
                .timeoutMillis(timeoutMillis);
        if (password != null && !password.isEmpty()) {
            config.password(password);
        }
        client = RedisClient.builder()
                .hostAndPort(HostAndPort.from(hostAndPort))
                .clientConfig(config.build())
                .build();
    }

    @Override
    public byte[] get(byte[] key) {
        return key == null ? null : client.get(key);
    }

    @Override
    public byte[] set(byte[] key, byte[] value, int expire) {
        if (key == null) {
            return null;
        }
        if (expire > 0) {
            client.set(key, value, SetParams.setParams().ex(expire));
        } else {
            client.set(key, value);
        }
        return value;
    }

    @Override
    public void del(byte[] key) {
        if (key != null) {
            client.del(key);
        }
    }

    @Override
    public Long dbSize(byte[] pattern) {
        return (long) keys(pattern).size();
    }

    @Override
    public Set<byte[]> keys(byte[] pattern) {
        if (pattern == null) {
            return Collections.emptySet();
        }
        ScanParams params = new ScanParams().match(pattern).count(100);
        byte[] cursor = ScanParams.SCAN_POINTER_START_BINARY;
        // Redis may return a key more than once during SCAN. Arrays need content equality.
        Map<ByteBuffer, byte[]> keys = new LinkedHashMap<>();
        do {
            ScanResult<byte[]> result = client.scan(cursor, params);
            for (byte[] key : result.getResult()) {
                keys.putIfAbsent(ByteBuffer.wrap(key), key);
            }
            cursor = result.getCursorAsBytes();
        } while (!"0".equals(new String(cursor, java.nio.charset.StandardCharsets.US_ASCII)));
        return new LinkedHashSet<>(keys.values());
    }

    @Override
    public void close() {
        client.close();
    }
}
