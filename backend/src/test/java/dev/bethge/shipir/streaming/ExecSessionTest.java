package dev.bethge.shipir.streaming;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ExecHandle;
import dev.bethge.shipir.docker.client.ExecService;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real server and STOMP client; Docker is replaced by a scripted {@link ExecService}. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shipir.streaming.exec.idle-timeout=2s", "shipir.streaming.exec.sweep-interval=200ms",
        "shipir.streaming.exec.max-sessions-per-user=2"})
@Import(PostgresTestConfiguration.class)
class ExecSessionTest {

    static final String PASSWORD = "A-test-Passw0rd-for-exec!";
    static final String SECRET_INPUT = "super-secret-keystrokes-4711";

    @LocalServerPort
    int port;
    @Autowired
    UserRepository users;
    @Autowired
    DockerHostRepository hosts;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    org.springframework.context.ApplicationEventPublisher publisher;
    @MockitoBean
    ExecService execService;

    private final ExecHandle handle = mock(ExecHandle.class);
    private Consumer<byte[]> onOutput;
    private Consumer<Throwable> onEnd;
    private final List<List<String>> commands = new CopyOnWriteArrayList<>();
    private UUID hostId;

    /** One HTTP client with its own cookie jar per signed-in user. */
    private final class Client {
        final String username;
        final HttpClient http = HttpClient.newHttpClient();
        final Map<String, String> jar = new HashMap<>();
        String csrf;

        Client(String username, Role role) throws Exception {
            this.username = username;
            User u = users.findByUsername(username).orElseGet(() -> new User(username, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
            call(HttpRequest.newBuilder(URI.create(base() + "/api/auth/me")));
            var r = call(HttpRequest.newBuilder(URI.create(base() + "/api/auth/login"))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}")));
            assertThat(r.statusCode()).isEqualTo(200);
            call(HttpRequest.newBuilder(URI.create(base() + "/api/auth/me"))); // picks up the rotated XSRF cookie
            csrf = jar.get("XSRF-TOKEN");
        }

        HttpResponse<String> call(HttpRequest.Builder b) throws Exception {
            if (!jar.isEmpty()) {
                b.header("Cookie", jar.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining("; ")));
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            r.headers().allValues("set-cookie").forEach(c -> {
                String pair = c.split(";", 2)[0];
                int i = pair.indexOf('=');
                jar.put(pair.substring(0, i), pair.substring(i + 1));
            });
            return r;
        }

        HttpResponse<String> exec(String container, String body, boolean withCsrf) throws Exception {
            var b = HttpRequest.newBuilder(URI.create(base() + "/api/hosts/" + hostId + "/containers/" + container + "/exec"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (withCsrf) {
                b.header("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"));
            }
            return call(b);
        }

        String create(String container) throws Exception {
            var r = exec(container, "{}", true);
            assertThat(r.statusCode()).as(r.body() + jar).isEqualTo(201);
            return json(r.body(), "sessionId");
        }

        StompSession connect() throws Exception {
            WebSocketStompClient c = new WebSocketStompClient(new StandardWebSocketClient());
            c.setMessageConverter(new RawBytesConverter());
            WebSocketHttpHeaders h = new WebSocketHttpHeaders();
            h.add("Cookie", "JSESSIONID=" + jar.get("JSESSIONID") + "; XSRF-TOKEN=" + jar.get("XSRF-TOKEN"));
            StompHeaders connect = new StompHeaders();
            connect.add("X-XSRF-TOKEN", csrf);
            return c.connectAsync("ws://localhost:" + port + "/ws", h, connect, new StompSessionHandlerAdapter() {
            }).get(5, TimeUnit.SECONDS);
        }
    }

    private Client admin() throws Exception {
        return new Client("execadmin-" + UUID.randomUUID().toString().substring(0, 8), Role.ADMIN);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static String json(String body, String field) {
        var m = java.util.regex.Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(body);
        assertThat(m.find()).as(body).isTrue();
        return m.group(1);
    }

    @BeforeEach
    void setUp() {
        User admin = users.findByUsername("execadmin").orElseGet(() -> new User("execadmin", "x", Role.ADMIN));
        hostId = hosts.save(DockerHost.local("exec-" + UUID.randomUUID(), users.save(admin).getId())).getId();
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'EXEC_%'");
        when(execService.open(any(), any(DockerEndpoint.class), anyString(), anyList(), anyInt(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    commands.add(inv.getArgument(3));
                    onOutput = inv.getArgument(6);
                    onEnd = inv.getArgument(7);
                    return handle;
                });
    }

    @AfterEach
    void cleanUp() {
        hosts.deleteById(hostId);
    }

    private BlockingQueue<String> subscribe(StompSession s, String destination) {
        BlockingQueue<String> q = new LinkedBlockingQueue<>();
        s.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                q.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
        return q;
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private List<Map<String, Object>> auditRows(Client c, String action) {
        return jdbc.queryForList("SELECT * FROM audit_log WHERE action = ? AND username = ?", action, c.username);
    }

    // --- create ---

    @Test
    void nonAdminsCannotCreateConsoles() throws Exception {
        assertThat(new Client("execop", Role.OPERATOR).exec("web", "{}", true).statusCode()).isEqualTo(403);
        assertThat(new Client("execviewer", Role.VIEWER).exec("web", "{}", true).statusCode()).isEqualTo(403);
        verify(execService, never()).open(any(), any(DockerEndpoint.class), anyString(), anyList(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void createNeedsCsrfToken() throws Exception {
        assertThat(admin().exec("web", "{}", false).statusCode()).isEqualTo(403);
    }

    @Test
    void adminGetsSessionAndDefaultShellIsSh() throws Exception {
        Client admin = admin();
        var r = admin.exec("web", "", true);
        assertThat(r.statusCode()).isEqualTo(201);
        String id = json(r.body(), "sessionId");
        assertThat(r.body()).contains("/topic/exec/" + id).contains("/app/exec/" + id + "/input");
        assertThat(commands).containsExactly(List.of("/bin/sh"));
        verify(execService).open(eq(hostId), any(DockerEndpoint.class), eq("web"), eq(List.of("/bin/sh")), eq(80), eq(24), any(), any());
    }

    @Test
    void shellIsAnAllowList() throws Exception {
        Client admin = admin();
        assertThat(admin.exec("web", "{\"shell\":\"/bin/bash\",\"cols\":120,\"rows\":40}", true).statusCode()).isEqualTo(201);
        assertThat(commands).containsExactly(List.of("/bin/bash"));
        for (String bad : new String[]{"/bin/zsh", "rm -rf /", "/bin/sh -c id", "sh", "/bin/bash;id"}) {
            var r = admin.exec("web", "{\"shell\":\"" + bad + "\"}", true);
            assertThat(r.statusCode()).as(bad).isEqualTo(400);
            assertThat(r.body()).contains("INVALID_SHELL");
        }
        assertThat(admin.exec("web", "{\"cols\":0}", true).statusCode()).isEqualTo(400);
        assertThat(admin.exec("../etc", "{}", true).statusCode()).isIn(400, 404);
        assertThat(commands).hasSize(1);
    }

    @Test
    void dockerErrorsBecomeStableCodes() throws Exception {
        Client admin = admin();
        when(execService.open(any(), any(DockerEndpoint.class), eq("stopped"), anyList(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.INVALID_STATE, "not running", null));
        when(execService.open(any(), any(DockerEndpoint.class), eq("ghost"), anyList(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "none", null));
        var stopped = admin.exec("stopped", "{}", true);
        assertThat(stopped.statusCode()).isEqualTo(409);
        assertThat(stopped.body()).contains("INVALID_CONTAINER_STATE");
        var ghost = admin.exec("ghost", "{}", true);
        assertThat(ghost.statusCode()).isEqualTo(404);
        assertThat(ghost.body()).contains("RESOURCE_NOT_FOUND");
    }

    @Test
    void sessionsPerUserAreLimited() throws Exception {
        Client admin = admin();
        admin.create("a");
        admin.create("b");
        var r = admin.exec("c", "{}", true);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(r.body()).contains("EXEC_LIMIT_REACHED");
    }

    // --- stream ---

    @Test
    void outputIsDeliveredEvenWhenItArrivedBeforeSubscribe() throws Exception {
        Client admin = admin();
        String id = admin.create("web");
        onOutput.accept("early\n".getBytes(StandardCharsets.UTF_8));
        StompSession s = admin.connect();
        BlockingQueue<String> q = subscribe(s, "/topic/exec/" + id);
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"seq\":1").contains("\"type\":\"OUTPUT\"")
                .contains(b64("early\n"));
        onOutput.accept("live".getBytes(StandardCharsets.UTF_8));
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"seq\":2").contains(b64("live"));
        s.disconnect();
    }

    @Test
    void inputAndResizeReachTheExec() throws Exception {
        Client admin = admin();
        String id = admin.create("web");
        StompSession s = admin.connect();
        subscribe(s, "/topic/exec/" + id);
        s.send("/app/exec/" + id + "/input", "ls\n".getBytes(StandardCharsets.UTF_8));
        s.send("/app/exec/" + id + "/resize", "{\"cols\":100,\"rows\":30}".getBytes(StandardCharsets.UTF_8));
        verify(handle, org.mockito.Mockito.timeout(5000)).write("ls\n".getBytes(StandardCharsets.UTF_8));
        verify(handle, org.mockito.Mockito.timeout(5000)).resize(100, 30);
        s.disconnect();
    }

    @Test
    void invalidResizeAndOversizedInputAreRefused() throws Exception {
        Client admin = admin();
        String id = admin.create("web");
        StompSession s = admin.connect();
        subscribe(s, "/topic/exec/" + id);
        s.send("/app/exec/" + id + "/resize", "{\"cols\":0,\"rows\":30}".getBytes(StandardCharsets.UTF_8));
        await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
        verify(handle, never()).resize(anyInt(), anyInt());
        // the refused frame ended the STOMP connection, which closes the console
        verify(handle, org.mockito.Mockito.timeout(5000)).close();
    }

    @Test
    void processExitSendsEndedAndAudits() throws Exception {
        Client admin = admin();
        String id = admin.create("web");
        StompSession s = admin.connect();
        BlockingQueue<String> q = subscribe(s, "/topic/exec/" + id);
        onEnd.accept(null);
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"type\":\"STATUS\"").contains("\"status\":\"ENDED\"")
                .contains("\"reason\":\"EXITED\"");
        verify(handle, org.mockito.Mockito.timeout(5000)).close();
        await().atMost(5, TimeUnit.SECONDS).until(() -> auditRows(admin, "EXEC_END").size() == 1);
        s.disconnect();
    }

    @Test
    void endedBeforeSubscribeIsStillReported() throws Exception {
        Client admin = admin();
        String id = admin.create("web");
        onOutput.accept("bye".getBytes(StandardCharsets.UTF_8));
        onEnd.accept(null);
        StompSession s = admin.connect();
        BlockingQueue<String> q = subscribe(s, "/topic/exec/" + id);
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains(b64("bye"));
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"status\":\"ENDED\"");
        s.disconnect();
    }

    // --- access ---

    @Test
    void otherAdminCannotAttachOrSend() throws Exception {
        Client owner = admin();
        String id = owner.create("web");
        Client other = admin();

        StompSession sub = other.connect();
        subscribe(sub, "/topic/exec/" + id);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !sub.isConnected());

        StompSession send = other.connect();
        send.send("/app/exec/" + id + "/input", SECRET_INPUT.getBytes(StandardCharsets.UTF_8));
        await().atMost(5, TimeUnit.SECONDS).until(() -> !send.isConnected());
        verify(handle, never()).write(any());
        verify(handle, never()).close();
    }

    @Test
    void nonAdminsCannotSubscribeOrSend() throws Exception {
        Client owner = admin();
        String id = owner.create("web");
        for (Role role : new Role[]{Role.OPERATOR, Role.VIEWER}) {
            Client c = new Client("exec" + role.name().toLowerCase(), role);
            StompSession sub = c.connect();
            subscribe(sub, "/topic/exec/" + id);
            await().atMost(5, TimeUnit.SECONDS).until(() -> !sub.isConnected());
            StompSession send = c.connect();
            send.send("/app/exec/" + id + "/input", "x".getBytes(StandardCharsets.UTF_8));
            await().atMost(5, TimeUnit.SECONDS).until(() -> !send.isConnected());
        }
        verify(handle, never()).write(any());
    }

    @Test
    void unknownSessionAndOtherAppDestinationsAreRefused() throws Exception {
        Client admin = admin();
        for (String dest : new String[]{"/app/exec/" + UUID.randomUUID() + "/input", "/app/other", "/app/exec/x/kill"}) {
            StompSession s = admin.connect();
            s.send(dest, "x".getBytes(StandardCharsets.UTF_8));
            await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
        }
    }

    @Test
    void secondSubscriptionToTheSameSessionIsRefused() throws Exception {
        Client admin = admin();
        String id = admin.create("web");
        StompSession first = admin.connect();
        subscribe(first, "/topic/exec/" + id);
        StompSession second = admin.connect();
        subscribe(second, "/topic/exec/" + id);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !second.isConnected());
        assertThat(first.isConnected()).isTrue();
        first.disconnect();
    }

    // --- re-authorization ---

    private void putRole(Client actor, Client target, String role) throws Exception {
        UUID id = users.findByUsername(target.username).orElseThrow().getId();
        var r = actor.call(HttpRequest.newBuilder(URI.create(base() + "/api/users/" + id + "/role"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", actor.jar.get("XSRF-TOKEN"))
                .PUT(HttpRequest.BodyPublishers.ofString("{\"role\":\"" + role + "\"}")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    @Test
    void demotedAdminsConsoleIsClosedAndInputRefused() throws Exception {
        Client owner = admin();
        Client other = admin();
        String id = owner.create("web");
        StompSession s = owner.connect();
        BlockingQueue<String> q = subscribe(s, "/topic/exec/" + id);
        s.send("/app/exec/" + id + "/input", "a".getBytes(StandardCharsets.UTF_8));
        verify(handle, org.mockito.Mockito.timeout(5000)).write(any());

        putRole(other, owner, "OPERATOR");

        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"status\":\"ENDED\"").contains("ACCESS_REVOKED");
        verify(handle, org.mockito.Mockito.timeout(5000)).close();
        s.send("/app/exec/" + id + "/input", SECRET_INPUT.getBytes(StandardCharsets.UTF_8));
        await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
        verify(handle, never()).write(SECRET_INPUT.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void disabledAdminsConsoleIsClosed() throws Exception {
        Client owner = admin();
        Client other = admin();
        String id = owner.create("web");
        StompSession s = owner.connect();
        BlockingQueue<String> q = subscribe(s, "/topic/exec/" + id);
        UUID ownerId = users.findByUsername(owner.username).orElseThrow().getId();
        var r = other.call(HttpRequest.newBuilder(URI.create(base() + "/api/users/" + ownerId + "/disable"))
                .header("X-XSRF-TOKEN", other.jar.get("XSRF-TOKEN")).POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("ACCESS_REVOKED");
        verify(handle, org.mockito.Mockito.timeout(5000)).close();
    }

    @Test
    void stillAuthorizedAdminKeepsTheConsoleWhenOnlyTheirPasswordChanges() throws Exception {
        Client owner = admin();
        String id = owner.create("web");
        StompSession s = owner.connect();
        subscribe(s, "/topic/exec/" + id);
        publisher.publishEvent(new dev.bethge.shipir.common.security.UserAccessChangedEvent(owner.username));
        s.send("/app/exec/" + id + "/input", "ok".getBytes(StandardCharsets.UTF_8));
        verify(handle, org.mockito.Mockito.timeout(5000)).write("ok".getBytes(StandardCharsets.UTF_8));
        verify(handle, never()).close();
        s.disconnect();
    }

    // --- closing ---

    @Test
    void disconnectClosesTheExec() throws Exception {
        Client admin = admin();
        String id = admin.create("web");
        StompSession s = admin.connect();
        subscribe(s, "/topic/exec/" + id);
        s.send("/app/exec/" + id + "/input", "a".getBytes(StandardCharsets.UTF_8));
        verify(handle, org.mockito.Mockito.timeout(5000)).write(any());
        s.disconnect();
        verify(handle, org.mockito.Mockito.timeout(5000)).close();
    }

    @Test
    void idleSessionIsClosed() throws Exception {
        Client admin = admin();
        String id = admin.create("web");
        StompSession s = admin.connect();
        BlockingQueue<String> q = subscribe(s, "/topic/exec/" + id);
        String frame = q.poll(10, TimeUnit.SECONDS);
        assertThat(frame).contains("\"status\":\"ENDED\"").contains("IDLE_TIMEOUT");
        verify(handle, org.mockito.Mockito.timeout(5000)).close();
        s.disconnect();
    }

    @Test
    void logoutClosesTheExec() throws Exception {
        Client admin = admin();
        String id = admin.create("web");
        StompSession s = admin.connect();
        subscribe(s, "/topic/exec/" + id);
        s.send("/app/exec/" + id + "/input", "a".getBytes(StandardCharsets.UTF_8));
        verify(handle, org.mockito.Mockito.timeout(5000)).write(any());
        var r = admin.call(HttpRequest.newBuilder(URI.create(base() + "/api/auth/logout"))
                .header("X-XSRF-TOKEN", admin.jar.get("XSRF-TOKEN")).POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(r.statusCode()).isIn(200, 204);
        verify(handle, org.mockito.Mockito.timeout(5000)).close();
        await().atMost(5, TimeUnit.SECONDS).until(() -> auditRows(admin, "EXEC_END").size() == 1);
    }

    @Test
    void logoutClosesAnExecThatWasNeverAttached() throws Exception {
        Client admin = admin();
        admin.create("web");
        admin.call(HttpRequest.newBuilder(URI.create(base() + "/api/auth/logout"))
                .header("X-XSRF-TOKEN", admin.jar.get("XSRF-TOKEN")).POST(HttpRequest.BodyPublishers.noBody()));
        verify(handle, org.mockito.Mockito.timeout(5000)).close();
        await().atMost(5, TimeUnit.SECONDS).until(
                () -> auditRows(admin, "EXEC_END").stream().anyMatch(r -> r.get("details").toString().contains("LOGOUT")));
    }

    // --- audit ---

    @Test
    void startAndEndAreAuditedWithoutInputContent() throws Exception {
        Client admin = admin();
        String id = admin.create("web");
        StompSession s = admin.connect();
        subscribe(s, "/topic/exec/" + id);
        s.send("/app/exec/" + id + "/input", SECRET_INPUT.getBytes(StandardCharsets.UTF_8));
        verify(handle, org.mockito.Mockito.timeout(5000)).write(any());
        s.disconnect();
        await().atMost(5, TimeUnit.SECONDS).until(() -> auditRows(admin, "EXEC_END").size() == 1);

        var start = auditRows(admin, "EXEC_START");
        assertThat(start).hasSize(1);
        assertThat(start.get(0)).containsEntry("username", admin.username).containsEntry("target_id", "web")
                .containsEntry("outcome", "SUCCESS");
        var end = auditRows(admin, "EXEC_END");
        assertThat(end.get(0)).containsEntry("username", admin.username).containsEntry("target_id", "web");
        assertThat(end.get(0).get("details").toString()).contains("DISCONNECTED");
        List<String> everything = new ArrayList<>();
        jdbc.queryForList("SELECT * FROM audit_log").forEach(row -> everything.add(row.toString()));
        assertThat(everything.toString()).doesNotContain(SECRET_INPUT).doesNotContain(b64(SECRET_INPUT));
    }

    @Test
    void failedStartIsAuditedAsFailure() throws Exception {
        Client admin = admin();
        when(execService.open(any(), any(DockerEndpoint.class), eq("stopped"), anyList(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.INVALID_STATE, "not running", null));
        admin.exec("stopped", "{}", true);
        var start = auditRows(admin, "EXEC_START");
        assertThat(start).hasSize(1);
        assertThat(start.get(0)).containsEntry("outcome", "FAILURE");
        assertThat(auditRows(admin, "EXEC_END")).isEmpty();
    }

    /** Accepts any content type so the test sees the raw JSON bytes the server sent. */
    private static final class RawBytesConverter extends org.springframework.messaging.converter.AbstractMessageConverter {
        RawBytesConverter() {
            super(org.springframework.util.MimeTypeUtils.ALL);
        }

        @Override
        protected boolean supports(Class<?> clazz) {
            return clazz == byte[].class;
        }

        @Override
        protected Object convertFromInternal(org.springframework.messaging.Message<?> message, Class<?> targetClass, Object hint) {
            return message.getPayload();
        }

        @Override
        protected Object convertToInternal(Object payload, org.springframework.messaging.MessageHeaders headers, Object hint) {
            return payload;
        }
    }
}
