package com.blog.auth;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.blog.common.ApiException;
import com.blog.dto.BlogEditRequest;
import com.blog.entity.Blog;
import com.blog.entity.User;
import com.blog.service.BlogService;
import com.blog.service.UserService;
import com.blog.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import com.blog.common.Result;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"blog.jwt.expire=60", "blog.http.max-request-bytes=4096"})
@Import(AuthHttpTest.ProbeConfiguration.class)
class AuthHttpTest {
    @MockitoBean DataSource dataSource;
    @MockitoBean UserService users;
    @MockitoBean BlogService blogs;
    @MockitoBean AuthTokenStore tokens;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    private User admin;
    private final Map<String, Long> allowed = new ConcurrentHashMap<>();
    private static final String PASSWORD = "test-only-correct-password";

    @BeforeEach
    void fixture() {
        allowed.clear();
        jwt.setExpire(60);
        admin = new User().setId(1L).setUsername("admin").setPassword(encoder.encode(PASSWORD)).setStatus(0);
        when(users.getOne(any(Wrapper.class))).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            query.getSqlSegment();
            return query.getParamNameValuePairs().containsValue("admin") ? admin : null;
        });
        when(users.getById(any(Serializable.class))).thenAnswer(invocation ->
                Long.valueOf(1).equals(invocation.getArgument(0)) ? admin : null);
        when(users.updateById(any(User.class))).thenReturn(true);
        when(tokens.allowLoginAttempt(anyString(), anyString())).thenReturn(true);
        doAnswer(invocation -> { allowed.put(invocation.getArgument(0), invocation.getArgument(1)); return null; })
                .when(tokens).allow(anyString(), anyLong(), anyInt());
        when(tokens.contains(anyString(), anyLong())).thenAnswer(invocation ->
                Long.valueOf((long) invocation.getArgument(1)).equals(allowed.get(invocation.getArgument(0))));
        doAnswer(invocation -> { allowed.remove(invocation.getArgument(0)); return null; })
                .when(tokens).revoke(anyString());
    }

    @Test
    void loginIgnoresStaleTokenExposesHeaderAndReturnsSafeProfile() throws Exception {
        HttpResponse<String> response = login("obsolete-token");
        assertEquals(200, response.statusCode());
        assertFalse(response.headers().firstValue("Authorization").orElseThrow().isBlank());
        assertEquals("Authorization", response.headers().firstValue("Access-Control-Expose-Headers").orElseThrow());
        assertEquals("20001", json.readTree(response.body()).path("code").asText());
        assertFalse(json.readTree(response.body()).path("data").has("password"));
        verify(users).updateById(argThat((User user) -> user.getId() == 1 && user.getLastLogin() != null
                && user.getPassword() == null && user.getStatus() == null));
        verifyNoInteractions(dataSource);
    }

    @Test
    void anonymousPrivateRoutesAre401AndPublicListRemainsOpen() throws Exception {
        assertEquals(401, request("GET", "/me", null, null).statusCode());
        assertEquals(401, request("GET", "/user/1", null, null).statusCode());
        assertEquals(401, request("POST", "/blog/edit", "{}", null).statusCode());
        assertEquals(200, request("GET", "/blogs", null, null).statusCode());
    }

    @Test
    void validTokenAllowsOwnProfileAndRejectsOtherUsers() throws Exception {
        String token = token();
        assertEquals(200, request("GET", "/me", null, "Bearer " + token).statusCode());
        assertEquals(200, request("GET", "/user/1", null, token).statusCode());
        assertEquals(403, request("GET", "/user/2", null, token).statusCode());
    }

    @Test
    void malformedExpiredUnregisteredAndTamperedTokensAre401() throws Exception {
        assertEquals(401, request("GET", "/me", null, "bad.token").statusCode());
        assertEquals(401, request("GET", "/me", null, jwt.generateToken(1)).statusCode());
        String token = token();
        String[] parts = token.split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        assertEquals(401, request("GET", "/me", null, String.join(".", parts)).statusCode());
        jwt.setExpire(-60);
        String expired = jwt.generateToken(1);
        allowed.put(expired, 1L);
        assertEquals(401, request("GET", "/me", null, expired).statusCode());
    }

    @Test
    void lockingAUserImmediatelyInvalidatesPreviouslyAuthenticatedRequests() throws Exception {
        String token = token();
        assertEquals(200, request("GET", "/me", null, token).statusCode());
        admin.setStatus(-1);
        assertEquals(403, request("GET", "/me", null, token).statusCode());
        assertEquals(403, login(null).statusCode());
    }

    @Test
    void logoutRevokesOnlyItsTokenAndDoesNotCreateSessionCookies() throws Exception {
        String first = token();
        String second = token();
        assertNotEquals(first, second);
        HttpResponse<String> response = request("POST", "/logout", "{}", first);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        assertEquals(401, request("GET", "/me", null, first).statusCode());
        assertEquals(200, request("GET", "/me", null, second).statusCode());
        assertEquals(200, request("GET", "/logout", null, second).statusCode());
        assertEquals(401, request("GET", "/me", null, second).statusCode());
    }

    @Test
    void validationThrottleAndRequestLimitUseAppropriateHttpStatuses() throws Exception {
        assertEquals(400, request("POST", "/login", "{}", null).statusCode());
        assertEquals(400, request("POST", "/login", "{", null).statusCode());
        assertEquals(401, request("POST", "/login", "{\"username\":\"admin\",\"password\":\"wrong\"}", null).statusCode());
        assertEquals(405, request("GET", "/login", null, null).statusCode());
        assertEquals(413, request("POST", "/login", " ".repeat(4097), null).statusCode());
        when(tokens.allowLoginAttempt(anyString(), anyString())).thenReturn(false);
        assertEquals(429, login(null).statusCode());
    }

    @Test
    void corsRejectsUnlistedOrigins() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(url("/blogs")))
                    .header("Origin", "https://untrusted.invalid").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
            assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        }
    }

    @Test
    void vendorAndChunkedJsonCannotBypassBodyLimit() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(url("/login")))
                    .header("Content-Type", "application/vnd.api+json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(
                            " ".repeat(4097).getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(413, response.statusCode());
        }
    }

    @Test
    void unavailableTokenStoreFailsClosedWith503() throws Exception {
        String token = token();
        when(tokens.contains(anyString(), anyLong()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "认证服务暂时不可用"));
        assertEquals(503, request("GET", "/me", null, token).statusCode());
    }

    @Test
    void shiroAnnotationsProtectRoutesOutsideTheFilterFallbackList() throws Exception {
        assertEquals(401, request("GET", "/test-auth-probe", null, null).statusCode());
        assertEquals(200, request("GET", "/test-auth-probe", null, token()).statusCode());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean ProbeController probeController() { return new ProbeController(); }
    }

    @RestController
    @TestComponent
    static class ProbeController {
        @GetMapping("/test-auth-probe")
        @RequiresAuthentication
        public Result probe() { return Result.ok(); }
    }

    @Test
    void editUsesAuthenticatedAuthorAndCannotBindClientOwnership() throws Exception {
        String token = token();
        when(blogs.saveForAuthor(eq(1L), any())).thenReturn(new Blog().setId(7L).setUserId(1L));
        String body = "{\"title\":\"safe\",\"description\":\"test\",\"content\":\"hello\",\"status\":0,\"userId\":999,\"created\":\"2000-01-01\"}";
        assertEquals(200, request("POST", "/blog/edit", body, token).statusCode());
        verify(blogs).saveForAuthor(eq(1L), eq(new BlogEditRequest(null, "safe", "test", "hello", 0)));
        when(blogs.saveForAuthor(eq(1L), any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "不是文章作者"));
        assertEquals(403, request("POST", "/blog/edit", body, token).statusCode());
    }

    private String token() throws Exception {
        HttpResponse<String> response = login(null);
        assertEquals(200, response.statusCode(), response.body());
        return response.headers().firstValue("Authorization").orElseThrow();
    }

    private HttpResponse<String> login(String token) throws Exception {
        return request("POST", "/login", json.writeValueAsString(Map.of("username", "admin", "password", PASSWORD)), token);
    }

    private String url(String path) { return "http://127.0.0.1:" + port + path; }

    private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Origin", "http://localhost:5173");
        if (token != null) builder.header("Authorization", token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
