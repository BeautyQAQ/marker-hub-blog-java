package com.blog.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blog.entity.User;
import com.blog.service.UserService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;

/** Explicit one-shot bootstrap only; never overwrites or seeds an ordinary application start. */
@Component
@Profile("bootstrap-admin")
public class AdminBootstrapRunner implements CommandLineRunner {
    private final UserService users;
    private final PasswordEncoder passwords;

    public AdminBootstrapRunner(UserService users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Override
    public void run(String... args) throws Exception {
        String username = System.getenv().getOrDefault("BLOG_ADMIN_USERNAME", "admin");
        char[] password;
        String supplied = System.getenv("BLOG_ADMIN_PASSWORD");
        Console console = System.console();
        if (supplied != null) password = supplied.toCharArray();
        else if (console != null) password = console.readPassword("请输入站长密码：");
        else {
            String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            password = line == null ? null : line.toCharArray();
        }
        try {
            initialize(username, password == null ? null : new String(password));
            System.out.println("站长账号已创建，初始化程序退出。");
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    public void initialize(String username, String password) {
        if (username == null || !username.matches("[A-Za-z0-9_.-]{3,64}")) {
            throw new IllegalArgumentException("站长用户名须为3至64位字母、数字、点、下划线或连字符");
        }
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码至少8个字符，UTF-8长度不得超过72字节");
        }
        if (users.count(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0) {
            throw new IllegalStateException("该账号已存在，初始化不会覆盖已有账号");
        }
        User user = new User().setUsername(username).setPassword(passwords.encode(password))
                .setStatus(0).setCreated(new Date());
        if (!users.save(user)) throw new IllegalStateException("站长账号创建失败");
    }
}
