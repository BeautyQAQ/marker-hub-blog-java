package com.blog.config;

import cn.hutool.json.JSONUtil;
import com.blog.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;

/** Bound JSON allocation for both Content-Length and chunked requests before JSON deserialization. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class JsonRequestLimitFilter extends OncePerRequestFilter {
    private final int maximum;

    public JsonRequestLimitFilter(@Value("${blog.http.max-request-bytes:2097152}") int maximum) {
        if (maximum < 1024 || maximum > 16 * 1024 * 1024) throw new IllegalArgumentException("请求大小限制配置不合法");
        this.maximum = maximum;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String type = request.getContentType();
        String mediaType = type == null ? "" : type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!mediaType.startsWith("application/") || !(mediaType.endsWith("/json") || mediaType.endsWith("+json"))) {
            chain.doFilter(request, response); return;
        }
        if (request.getContentLengthLong() > maximum) { reject(response); return; }
        byte[] body = request.getInputStream().readNBytes(maximum + 1);
        if (body.length > maximum) { reject(response); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        throw new IllegalStateException("异步JSON读取不受支持");
                    }
                };
            }
        }, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(Result.failed("请求内容过大", null)));
    }
}
