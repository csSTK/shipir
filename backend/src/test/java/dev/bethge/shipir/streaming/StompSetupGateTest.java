package dev.bethge.shipir.streaming;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The WebSocket honours the first-start setup gate: no STOMP session while a setup step (password) is pending. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class StompSetupGateTest {

    private static final String ONE_TIME = "One-Time-Passw0rd-for-stomp!";
    private static final String NEW_PASSWORD = "A-brand-new-Passw0rd-for-stomp!";

    @LocalServerPort
    int port;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> jar = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        User u = users.findByUsername("stompsetup").orElseGet(() -> new User("stompsetup", "x", Role.OPERATOR));
        u.changePasswordHash(encoder.encode(ONE_TIME), java.time.Instant.now());
        users.save(u);
        // a user with a one-time password: setup step PASSWORD is pending
        jdbc.update("UPDATE users SET password_changed_at = NULL WHERE username = 'stompsetup'");
        jar.clear();
        call(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me")));
        var r = call(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"stompsetup\",\"password\":\"" + ONE_TIME + "\"}")));
        assertThat(r.statusCode()).isEqualTo(200);
        call(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))); // fresh CSRF token
    }

    @Test
    void connectIsRefusedWhileThePasswordStepIsPending() throws Exception {
        List<String> errors = new CopyOnWriteArrayList<>();
        WebSocketHttpHeaders h = new WebSocketHttpHeaders();
        h.add("Cookie", "JSESSIONID=" + jar.get("JSESSIONID") + "; XSRF-TOKEN=" + jar.get("XSRF-TOKEN"));
        StompHeaders connect = new StompHeaders();
        connect.add("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"));
        WebSocketStompClient c = new WebSocketStompClient(new StandardWebSocketClient());
        c.setMessageConverter(new org.springframework.messaging.converter.ByteArrayMessageConverter());
        var future = c.connectAsync("ws://localhost:" + port + "/ws", h, connect, new StompSessionHandlerAdapter() {
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errors.add(String.valueOf(headers.getFirst("message")));
            }
        });
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        // the refusal comes from the interceptor (ERROR frame), not from a timeout or an unrelated failure
        await().atMost(5, TimeUnit.SECONDS).until(() -> errors.stream().anyMatch(m -> m.contains("SETUP_REQUIRED")));
    }

    @Test
    void connectWorksAfterThePasswordWasChanged() throws Exception {
        var r = call(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/password"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"currentPassword\":\"" + ONE_TIME + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(204);
        call(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))); // rotated session

        StompSession s = connect();
        assertThat(s.isConnected()).isTrue();
        s.disconnect();
    }

    private StompSession connect() throws Exception {
        WebSocketHttpHeaders h = new WebSocketHttpHeaders();
        h.add("Cookie", "JSESSIONID=" + jar.get("JSESSIONID") + "; XSRF-TOKEN=" + jar.get("XSRF-TOKEN"));
        StompHeaders connect = new StompHeaders();
        connect.add("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"));
        WebSocketStompClient c = new WebSocketStompClient(new StandardWebSocketClient());
        c.setMessageConverter(new org.springframework.messaging.converter.ByteArrayMessageConverter());
        return c.connectAsync("ws://localhost:" + port + "/ws", h, connect, new StompSessionHandlerAdapter() {
        }).get(5, TimeUnit.SECONDS);
    }

    private HttpResponse<String> call(HttpRequest.Builder b) throws Exception {
        if (!jar.isEmpty()) {
            b.header("Cookie", jar.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(java.util.stream.Collectors.joining("; ")));
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        r.headers().allValues("set-cookie").forEach(c -> {
            String pair = c.split(";", 2)[0];
            int i = pair.indexOf('=');
            jar.put(pair.substring(0, i), pair.substring(i + 1));
        });
        return r;
    }
}
