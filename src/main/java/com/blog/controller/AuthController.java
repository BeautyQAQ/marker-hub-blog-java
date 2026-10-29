package com.blog.controller;

import com.blog.auth.AuthService;
import com.blog.auth.AuthTokens;
import com.blog.auth.LoginRequest;
import com.blog.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!bootstrap-admin")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/login")
    public Result login(@Valid @RequestBody LoginRequest input, HttpServletRequest request,
                        HttpServletResponse response) {
        AuthService.Login login = auth.login(input, request.getRemoteAddr());
        response.setHeader("Authorization", login.token());
        return Result.successful("登录成功", login.profile());
    }

    @GetMapping("/me")
    @RequiresAuthentication
    public Result me() { return Result.successful("获取成功", SecurityUtils.getSubject().getPrincipal()); }

    @RequestMapping(value = "/logout", method = {RequestMethod.GET, RequestMethod.POST})
    @RequiresAuthentication
    public Result logout(HttpServletRequest request) {
        auth.logout(AuthTokens.resolve(request.getHeader("Authorization")));
        SecurityUtils.getSubject().logout();
        return Result.ok();
    }
}
