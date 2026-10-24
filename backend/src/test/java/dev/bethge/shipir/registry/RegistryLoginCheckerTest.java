package dev.bethge.shipir.registry;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryLoginCheckerTest {

    private static final String BASIC = "Basic " + Base64.getEncoder()
            .encodeToString("robot:s3cret".getBytes(StandardCharsets.UTF_8));

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String host() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    private static RegistryLoginChecker checker(boolean insecure) {
        return new RegistryLoginChecker(new RegistryProperties(insecure, Duration.ofSeconds(2)));
    }

    private void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @Test
    void basicRegistryAcceptsAndRejects() throws Exception {
        start();
        server.createContext("/v2/", ex -> {
            requests.add(ex.getRequestURI().getPath());
            if (BASIC.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                ex.sendResponseHeaders(200, -1);
            } else {
                ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"registry\"");
                ex.sendResponseHeaders(401, -1);
            }
            ex.close();
        });
        server.start();
        assertThat(checker(true).check(host(), "robot", "s3cret")).isEqualTo("OK");
        assertThat(checker(true).check(host(), "robot", "wrong")).isEqualTo("AUTH_FAILED");
        assertThat(requests).contains("/v2/");
    }

    @Test
    void bearerChallengeIsFollowedWithBasicCredentials() throws Exception {
        start();
        String realm = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        server.createContext("/v2/", ex -> {
            ex.getResponseHeaders().add("WWW-Authenticate",
                    "Bearer realm=\"" + realm + "\",service=\"registry.test\"");
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        server.createContext("/token", ex -> {
            requests.add(ex.getRequestURI().getRawQuery());
            boolean ok = BASIC.equals(ex.getRequestHeaders().getFirst("Authorization"));
            ex.sendResponseHeaders(ok ? 200 : 401, ok ? 2 : -1);
            if (ok) {
                ex.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            }
            ex.close();
        });
        server.start();
        assertThat(checker(true).check(host(), "robot", "s3cret")).isEqualTo("OK");
        assertThat(checker(true).check(host(), "robot", "wrong")).isEqualTo("AUTH_FAILED");
        assertThat(requests).contains("service=registry.test");
    }

    @Test
    void plainHttpIsNotUsedUnlessAllowed() throws Exception {
        start();
        server.createContext("/", ex -> {
            requests.add("hit");
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        assertThat(checker(false).check(host(), "robot", "s3cret")).isEqualTo("UNREACHABLE");
        assertThat(requests).isEmpty();
    }

    @Test
    void insecureBearerRealmIsRefusedWhenHttpNotAllowed() throws Exception {
        start();
        // registry itself is reached over (allowed) http, but the realm points elsewhere with a bad scheme
        server.createContext("/v2/", ex -> {
            ex.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"file:///etc/passwd\"");
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        server.start();
        assertThat(checker(true).check(host(), "robot", "s3cret")).isEqualTo("UNREACHABLE");
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        start();
        server.createContext("/v2/", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        assertThat(checker(true).check(host(), "robot", "s3cret")).isEqualTo("UNREACHABLE");
    }

    @Test
    void closedPortIsUnreachable() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        assertThat(checker(true).check("127.0.0.1:" + port, "robot", "s3cret")).isEqualTo("UNREACHABLE");
    }
}
