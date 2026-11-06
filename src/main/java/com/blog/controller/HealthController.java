package com.blog.controller;

import com.blog.shiro.JedisRedisManager;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/** Public probes expose status only, never connection settings or exception messages. */
@RestController
@Profile("!bootstrap-admin")
public class HealthController {
    private final DataSource database;
    private final JedisRedisManager redis;

    public HealthController(DataSource database, JedisRedisManager redis) {
        this.database = database;
        this.redis = redis;
    }

    @GetMapping("/health/live")
    public Health live() {
        return new Health("UP");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Health> ready() {
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            statement.execute("SELECT 1");
            if (!"PONG".equals(redis.ping())) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new Health("DOWN"));
            }
            return ResponseEntity.ok(new Health("UP"));
        } catch (Exception unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new Health("DOWN"));
        }
    }

    public record Health(String status) { }
}
