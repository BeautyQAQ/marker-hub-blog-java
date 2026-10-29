package com.blog.bootstrap;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.blog.VueBlogApplication;
import com.blog.entity.User;
import com.blog.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminBootstrapTest {
    @Test
    void refusesExistingAccountWithoutOverwritingIt() {
        UserService users = mock(UserService.class);
        when(users.count(any(Wrapper.class))).thenReturn(1L);
        AdminBootstrapRunner bootstrap = new AdminBootstrapRunner(users, new BCryptPasswordEncoder(4));
        assertThrows(IllegalStateException.class, () -> bootstrap.initialize("admin", "test-only-strong-password"));
        verify(users, never()).save(any(User.class));
    }

    @Test
    void hashesPasswordsAndRejectsInvalidBootstrapInput() {
        UserService users = mock(UserService.class);
        when(users.save(any(User.class))).thenReturn(true);
        BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
        AdminBootstrapRunner bootstrap = new AdminBootstrapRunner(users, passwords);
        assertThrows(IllegalArgumentException.class, () -> bootstrap.initialize("admin", "short"));
        bootstrap.initialize("admin", "test-only-strong-password");
        verify(users).save(argThat((User user) -> user.getStatus() == 0 && user.getCreated() != null
                && passwords.matches("test-only-strong-password", user.getPassword())));
    }

    @Test
    void explicitNonwebProfileReadsStdinCreatesAdminAndExits() throws Exception {
        String database = "jdbc:h2:mem:admin_bootstrap;MODE=MySQL;DB_CLOSE_DELAY=-1";
        var input = System.in;
        try {
            System.setIn(new ByteArrayInputStream("test-only-stdin-password\n".getBytes(StandardCharsets.UTF_8)));
            VueBlogApplication.main(new String[]{
                    "--spring.profiles.active=bootstrap-admin", "--spring.main.web-application-type=none",
                    "--spring.datasource.url=" + database, "--spring.datasource.username=sa", "--spring.datasource.password=",
                    "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.druid.filters=stat",
                    "--spring.datasource.druid.initial-size=0", "--spring.datasource.druid.min-idle=0",
                    "--spring.sql.init.mode=always", "--spring.sql.init.schema-locations=classpath:schema-bootstrap.sql"});
        } finally {
            System.setIn(input);
        }
        try (var connection = DriverManager.getConnection(database, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT username,password,status FROM m_user")) {
            assertTrue(result.next());
            assertEquals("admin", result.getString(1));
            assertTrue(new BCryptPasswordEncoder().matches("test-only-stdin-password", result.getString(2)));
            assertEquals(0, result.getInt(3));
            assertFalse(result.next());
        }
    }
}
