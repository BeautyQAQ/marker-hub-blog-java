package com.blog.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blog.common.ApiException;
import com.blog.entity.User;
import com.blog.service.UserService;
import com.blog.shiro.AccountProfile;
import com.blog.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.Date;

@Service
@Profile("!bootstrap-admin")
public class AuthService {
    private final UserService users;
    private final JwtUtil jwt;
    private final AuthTokenStore tokens;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    public AuthService(UserService users, JwtUtil jwt, AuthTokenStore tokens, PasswordEncoder passwords) {
        this.users = users;
        this.jwt = jwt;
        this.tokens = tokens;
        this.passwords = passwords;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public Login login(LoginRequest request, String clientIp) {
        String username = request.username().trim();
        if (username.isEmpty() || request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "账号或密码格式不合法");
        }
        if (!tokens.allowLoginAttempt(username, clientIp)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "登录尝试过于频繁，请稍后再试");
        }
        User user = users.getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        boolean matches = passwords.matches(request.password(), user == null || user.getPassword() == null
                ? dummyHash : user.getPassword());
        if (user == null || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        ensureActive(user);
        users.updateById(new User().setId(user.getId()).setLastLogin(new Date()));
        String token = jwt.generateToken(user.getId());
        Claims claims = jwt.getClaimByToken(token);
        int ttl = Math.toIntExact(Math.max(1, (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000));
        tokens.allow(token, user.getId(), ttl);
        return new Login(token, AccountProfile.from(user));
    }

    public AccountProfile authenticate(String token) {
        Claims claims = jwt.getClaimByToken(token);
        if (claims == null || claims.getExpiration() == null) throw unauthorized();
        final long userId;
        try { userId = Long.parseLong(claims.getSubject()); }
        catch (NumberFormatException invalid) { throw unauthorized(); }
        if (!tokens.contains(token, userId)) throw unauthorized();
        User user = users.getById(userId);
        if (user == null) throw unauthorized();
        ensureActive(user);
        return AccountProfile.from(user);
    }

    public void logout(String token) { tokens.revoke(token); }

    private void ensureActive(User user) {
        if (!Objects.equals(user.getStatus(), 0)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "账号已被停用");
        }
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "登录凭证无效或已过期，请重新登录");
    }

    public record Login(String token, AccountProfile profile) { }
}
