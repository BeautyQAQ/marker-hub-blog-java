package com.blog.shiro;

import cn.hutool.json.JSONUtil;
import com.blog.auth.AuthTokens;
import com.blog.common.ApiException;
import com.blog.common.Result;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.web.filter.authc.AuthenticatingFilter;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** A token is re-authenticated on every request; no servlet/Shiro session can bypass Redis or lock checks. */
public class JwtFilter extends AuthenticatingFilter {
    private final Set<String> allowedOrigins;

    public JwtFilter(String origins) {
        allowedOrigins = Arrays.stream(origins.split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) {
        return false;
    }

    @Override
    protected AuthenticationToken createToken(ServletRequest request, ServletResponse response) {
        return new JwtToken(AuthTokens.resolve(((HttpServletRequest) request).getHeader("Authorization")));
    }

    @Override
    protected boolean onAccessDenied(ServletRequest servletRequest, ServletResponse response) throws Exception {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        // A stale browser token must not prevent a fresh login.
        if ("POST".equals(request.getMethod()) && "/login".equals(path(request))) return true;
        try {
            if (AuthTokens.resolve(request.getHeader("Authorization")) == null) {
                if (requiresLogin(request)) {
                    error((HttpServletResponse) response, HttpStatus.UNAUTHORIZED, "请先登录");
                    return false;
                }
                return true;
            }
            return executeLogin(request, response);
        } catch (ApiException exception) {
            error((HttpServletResponse) response, exception.getStatus(), exception.getMessage());
            return false;
        }
    }

    @Override
    protected boolean onLoginFailure(AuthenticationToken token, AuthenticationException exception,
                                     ServletRequest request, ServletResponse response) {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        String message = "登录凭证无效或已过期，请重新登录";
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiException api) {
                status = api.getStatus(); message = api.getMessage(); break;
            }
            if (cause instanceof DataAccessException) {
                status = HttpStatus.SERVICE_UNAVAILABLE; message = "认证服务暂时不可用";
            }
        }
        try { error((HttpServletResponse) response, status, message); }
        catch (IOException failure) { throw new IllegalStateException("无法输出认证响应", failure); }
        return false;
    }

    @Override
    protected boolean preHandle(ServletRequest request, ServletResponse response) throws Exception {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse output = (HttpServletResponse) response;
        output.setHeader("Cache-Control", "no-store");
        String origin = http.getHeader("Origin");
        if (origin != null) {
            output.addHeader("Vary", "Origin");
            if (!allowedOrigins.contains(origin)) {
                error(output, HttpStatus.FORBIDDEN, "不允许的请求来源"); return false;
            }
            output.setHeader("Access-Control-Allow-Origin", origin);
            output.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
            output.setHeader("Access-Control-Allow-Headers", "Authorization,Content-Type");
            output.setHeader("Access-Control-Expose-Headers", "Authorization");
        }
        if ("OPTIONS".equals(http.getMethod())) { output.setStatus(204); return false; }
        return super.preHandle(request, response);
    }

    private String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private boolean requiresLogin(HttpServletRequest request) {
        String path = path(request);
        return path.equals("/me") || path.equals("/logout") || path.startsWith("/user/")
                || path.equals("/my/blogs") || path.equals("/blog/edit")
                || ("DELETE".equals(request.getMethod()) && path.startsWith("/blog/"));
    }

    private void error(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(Result.failed(message, null)));
    }
}
