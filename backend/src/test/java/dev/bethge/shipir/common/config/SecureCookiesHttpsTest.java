package dev.bethge.shipir.common.config;

import dev.bethge.shipir.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code shipir.security.https=true}: Secure on session and XSRF cookie, HSTS on every answer (real server). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shipir.security.https=true")
@Import(PostgresTestConfiguration.class)
class SecureCookiesHttpsTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "=")).findFirst().orElseThrow();
    }

    @Test
    void xsrfAndSessionCookiesAreSecureAndHstsIsSent() throws Exception {
        HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/auth/me")));
        assertThat(me.headers().firstValue("Strict-Transport-Security").orElseThrow())
                .contains("max-age=31536000").contains("includeSubDomains");
        String xsrf = cookie(me, "XSRF-TOKEN");
        assertThat(xsrf).contains("Secure");
        String token = xsrf.substring("XSRF-TOKEN=".length(), xsrf.indexOf(';'));

        HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"admin\",\"password\":\"test-initial-admin-password\"}")));
        assertThat(login.statusCode()).isEqualTo(200);
        List<String> cookies = login.headers().allValues("Set-Cookie");
        String session = cookies.stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertThat(session).contains("Secure").contains("HttpOnly").containsIgnoringCase("SameSite=Lax");
    }
}
