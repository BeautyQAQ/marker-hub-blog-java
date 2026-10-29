package com.blog;

import com.blog.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

/** Exercises actual Boot/Shiro wiring without connecting to configured external services. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StartupTest {
    @MockitoBean
    private DataSource dataSource;

    @MockitoBean
    private UserService userService;

    @LocalServerPort
    private int port;

    @Test
    void startsOnJava21AndServesTheExistingAnonymousRoute() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/user/9223372036854775807"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            verifyNoInteractions(dataSource);
        }
    }
}
