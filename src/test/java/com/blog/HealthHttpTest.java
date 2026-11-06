package com.blog;

import com.blog.shiro.JedisRedisManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthHttpTest {
    @MockitoBean DataSource database;
    @MockitoBean JedisRedisManager redis;
    @LocalServerPort int port;

    @BeforeEach
    void readyDependencies() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(database.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(redis.ping()).thenReturn("PONG");
    }

    @Test
    void liveIsIndependentOfDependenciesAndIgnoresStaleCredentials() throws Exception {
        HttpResponse<String> response = get("/health/live");
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"UP\"}", response.body());
        verifyNoInteractions(database, redis);
    }

    @Test
    void readyChecksBothDependenciesWithoutWritingData() throws Exception {
        HttpResponse<String> response = get("/health/ready");
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"UP\"}", response.body());
        verify(database.getConnection().createStatement()).execute("SELECT 1");
        verify(redis).ping();
    }

    @Test
    void unavailableDatabaseReturns503WithoutLeakingDetails() throws Exception {
        when(database.getConnection()).thenThrow(new SQLException("private database configuration"));
        HttpResponse<String> response = get("/health/ready");
        assertEquals(503, response.statusCode());
        assertEquals("{\"status\":\"DOWN\"}", response.body());
        verifyNoInteractions(redis);
    }

    @Test
    void unavailableRedisReturns503() throws Exception {
        when(redis.ping()).thenThrow(new IllegalStateException("private Redis configuration"));
        HttpResponse<String> response = get("/health/ready");
        assertEquals(503, response.statusCode());
        assertEquals("{\"status\":\"DOWN\"}", response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Authorization", "stale-probe-token").GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
