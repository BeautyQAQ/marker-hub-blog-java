package com.blog.shiro;

import org.apache.shiro.cache.Cache;
import org.apache.shiro.session.UnknownSessionException;
import org.apache.shiro.session.mgt.SimpleSession;
import org.crazycake.shiro.RedisCacheManager;
import org.crazycake.shiro.RedisSessionDAO;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JedisRedisManagerTest {
    @Test
    void binaryValuesAndTtlUseTheRealJedisProtocol() throws Exception {
        try (RespServer server = new RespServer();
             JedisRedisManager redis = new JedisRedisManager(server.address(), "", 3, 2000)) {
            byte[] key = {0, 13, 10, (byte) 255};
            byte[] value = {(byte) 254, 0, 13, 10};
            assertArrayEquals(value, redis.set(key, value, 15));
            assertArrayEquals(value, redis.get(key));
            assertEquals(15L, server.expirations.get(ByteBuffer.wrap(key)));
            redis.set(key, value, -1);
            assertFalse(server.expirations.containsKey(ByteBuffer.wrap(key)));
            redis.set(key, value, 0);
            assertFalse(server.expirations.containsKey(ByteBuffer.wrap(key)));
            redis.del(key);
            assertNull(redis.get(key));
            assertTrue(server.commands.contains("SELECT"));
            assertFalse(server.commands.contains("EXPIRE"), "TTL must be atomic with SET");
        }
    }

    @Test
    void scanTraversesAllPagesAndDeduplicatesBinaryKeys() throws Exception {
        try (RespServer server = new RespServer();
             JedisRedisManager redis = new JedisRedisManager(server.address(), null, 0, 2000)) {
            assertEquals(2, redis.keys(bytes("scan:*")).size());
            assertEquals(2L, redis.dbSize(bytes("scan:*")));
            assertEquals(4, Collections.frequency(server.commands, "SCAN"));
            assertFalse(server.commands.contains("KEYS"));
        }
    }

    @Test
    void legacyRedisCacheAndSessionDaoLinkAndRoundTripWithShiro3() throws Exception {
        try (RespServer server = new RespServer();
             JedisRedisManager redis = new JedisRedisManager(server.address(), null, 0, 2000)) {
            RedisCacheManager cacheManager = new RedisCacheManager();
            cacheManager.setRedisManager(redis);
            Cache<String, String> cache = cacheManager.getCache("compatibility");
            cache.put("entry", "cached-value");
            assertEquals("cached-value", cache.get("entry"));
            assertEquals(1, cache.keys().size());
            assertEquals(List.of("cached-value"), new ArrayList<>(cache.values()));
            assertEquals("cached-value", cache.remove("entry"));
            assertNull(cache.get("entry"));

            RedisSessionDAO sessions = new RedisSessionDAO();
            sessions.setRedisManager(redis);
            sessions.setSessionInMemoryEnabled(false);
            SimpleSession session = new SimpleSession();
            session.setTimeout(5000);
            session.setAttribute("state", "created");
            Serializable id = sessions.create(session);
            assertEquals("created", sessions.readSession(id).getAttribute("state"));
            session.setAttribute("state", "updated");
            sessions.update(session);
            assertEquals("updated", sessions.readSession(id).getAttribute("state"));
            sessions.delete(session);
            assertThrows(UnknownSessionException.class, () -> sessions.readSession(id));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** A loopback RESP fixture; no application database or Redis server is used. */
    private static final class RespServer implements AutoCloseable {
        private final ServerSocket server;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final Map<ByteBuffer, byte[]> values = new LinkedHashMap<>();
        private final Map<ByteBuffer, Long> expirations = new ConcurrentHashMap<>();
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        RespServer() throws IOException {
            server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            Thread.startVirtualThread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        sockets.add(socket);
                        Thread.startVirtualThread(() -> serve(socket));
                    } catch (IOException e) {
                        if (!server.isClosed()) {
                            failure.compareAndSet(null, e);
                        }
                    }
                }
            });
        }

        String address() {
            return "127.0.0.1:" + server.getLocalPort();
        }

        private void serve(Socket socket) {
            try (socket;
                 InputStream in = new BufferedInputStream(socket.getInputStream());
                 OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
                List<byte[]> command;
                while ((command = readCommand(in)) != null) {
                    respond(command, out);
                    out.flush();
                }
            } catch (IOException e) {
                if (!server.isClosed()) {
                    failure.compareAndSet(null, e);
                }
            }
        }

        private synchronized void respond(List<byte[]> command, OutputStream out) throws IOException {
            String name = new String(command.getFirst(), StandardCharsets.US_ASCII).toUpperCase();
            commands.add(name);
            switch (name) {
                case "CLIENT", "SELECT", "AUTH" -> out.write(bytes("+OK\r\n"));
                case "PING" -> out.write(bytes("+PONG\r\n"));
                case "GET" -> bulk(out, values.get(ByteBuffer.wrap(command.get(1))));
                case "SET" -> {
                    ByteBuffer key = ByteBuffer.wrap(command.get(1));
                    values.put(key, command.get(2));
                    expirations.remove(key);
                    if (command.size() == 5 && Arrays.equals(command.get(3), bytes("EX"))) {
                        expirations.put(key, Long.valueOf(new String(command.get(4), StandardCharsets.US_ASCII)));
                    }
                    out.write(bytes("+OK\r\n"));
                }
                case "DEL" -> {
                    ByteBuffer key = ByteBuffer.wrap(command.get(1));
                    boolean removed = values.remove(key) != null;
                    expirations.remove(key);
                    out.write(bytes(removed ? ":1\r\n" : ":0\r\n"));
                }
                case "SCAN" -> {
                    byte[] pattern = bytes("*");
                    for (int i = 2; i + 1 < command.size(); i += 2) {
                        if (Arrays.equals(command.get(i), bytes("MATCH"))) {
                            pattern = command.get(i + 1);
                        }
                    }
                    boolean pagedFixture = Arrays.equals(pattern, bytes("scan:*"));
                    boolean firstPage = Arrays.equals(command.get(1), bytes("0"));
                    List<byte[]> matches;
                    if (pagedFixture) {
                        matches = firstPage ? List.of(bytes("scan:one"), bytes("scan:two"))
                                : List.of(bytes("scan:one"));
                    } else {
                        String prefix = new String(pattern, StandardCharsets.UTF_8).replace("*", "");
                        matches = values.keySet().stream().map(ByteBuffer::array)
                                .filter(key -> new String(key, StandardCharsets.UTF_8).startsWith(prefix)).toList();
                    }
                    out.write(bytes("*2\r\n"));
                    bulk(out, bytes(pagedFixture && firstPage ? "5" : "0"));
                    out.write(bytes("*" + matches.size() + "\r\n"));
                    for (byte[] match : matches) {
                        bulk(out, match);
                    }
                }
                default -> throw new IOException("Unsupported fixture command: " + name);
            }
        }

        private static List<byte[]> readCommand(InputStream in) throws IOException {
            int marker = in.read();
            if (marker == -1) {
                return null;
            }
            if (marker != '*') {
                throw new IOException("Expected RESP command array");
            }
            int count = Integer.parseInt(line(in));
            List<byte[]> parts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (in.read() != '$') {
                    throw new IOException("Expected RESP bulk string");
                }
                int size = Integer.parseInt(line(in));
                byte[] part = in.readNBytes(size);
                if (part.length != size || in.read() != '\r' || in.read() != '\n') {
                    throw new EOFException("Incomplete RESP bulk string");
                }
                parts.add(part);
            }
            return parts;
        }

        private static String line(InputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int ch;
            while ((ch = in.read()) != '\r') {
                if (ch == -1) {
                    throw new EOFException("Incomplete RESP line");
                }
                buffer.write(ch);
            }
            if (in.read() != '\n') {
                throw new IOException("Invalid RESP line ending");
            }
            return buffer.toString(StandardCharsets.US_ASCII);
        }

        private static void bulk(OutputStream out, byte[] value) throws IOException {
            if (value == null) {
                out.write(bytes("$-1\r\n"));
            } else {
                out.write(bytes("$" + value.length + "\r\n"));
                out.write(value);
                out.write(bytes("\r\n"));
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket socket : sockets) {
                socket.close();
            }
            if (failure.get() != null) {
                throw new IOException("RESP fixture failed", failure.get());
            }
        }
    }
}
