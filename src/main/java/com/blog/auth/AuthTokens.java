package com.blog.auth;

import com.blog.common.ApiException;
import org.springframework.http.HttpStatus;

public final class AuthTokens {
    private AuthTokens() { }

    public static String resolve(String header) {
        if (header == null || header.isBlank()) return null;
        String token = header.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) token = token.substring(7).trim();
        if (token.isEmpty() || token.length() > 4096) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "登录凭证无效");
        }
        return token;
    }
}
