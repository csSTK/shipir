package dev.bethge.shipir.streaming;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.client.ContainerLogService;
import dev.bethge.shipir.docker.client.DockerEventService;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** SUBSCRIBE permission: deploy output needs view access to the run's deployment, logs/events need the whole host. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class StreamPermissionTest {

    static final String PASSWORD = "A-test-Passw0rd-for-streams!";

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
    ComposeOutputStreamService streams;
    @MockitoBean
    ContainerLogService logService;
    @MockitoBean
    DockerEventService eventService;
    @Autowired
    LogStreamHandler logStreams;
    @Autowired
    DockerEventStreamService eventStreams;
    @MockitoBean
    dev.bethge.shipir.docker.client.ContainerService containerService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> jar = new HashMap<>();
    private UUID userId;
    private UUID hostId;
    private UUID deploymentId;

    @BeforeEach
    void setUp() throws Exception {
        User u = users.findByUsername("streamop").orElseGet(() -> new User("streamop", "x", Role.OPERATOR));
        u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
        userId = users.save(u).getId();
        hostId = hosts.save(DockerHost.local("perm-" + UUID.randomUUID(), userId)).getId();
        deploymentId = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "VALUES ('d', ?, 'x', ?) RETURNING id", UUID.class, hostId, userId);
        login();
    }

    @AfterEach
    void cleanUp() {
        hosts.deleteById(hostId);
    }

    private String registerRun() {
        String runId = UUID.randomUUID().toString();
        streams.registerRun(runId, hostId, deploymentId);
        streams.start(runId);
        return runId;
    }

    @Test
    void deployOutputIsRefusedWithoutGrant() throws Exception {
        assertRefused("/topic/deployments/" + registerRun());
    }

    @Test
    void deployOutputOfUnknownRunIsRefusedForNonAdmin() throws Exception {
        assertRefused("/topic/deployments/" + UUID.randomUUID());
    }

    @Test
    void deployOutputIsAllowedWithDeploymentGrantOnly() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) VALUES (?, ?, 'VIEW')", userId, deploymentId);
        String runId = registerRun();
        assertAllowed("/topic/deployments/" + runId);
        // a deployment grant does not make the whole host visible
        assertRefused("/topic/hosts/" + hostId + "/events");
        assertRefused("/topic/hosts/" + hostId + "/containers/web/logs");
        verify(logService, never()).follow(any(), any(dev.bethge.shipir.docker.DockerEndpoint.class), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    @Test
    void logsAndEventsAreRefusedWithoutGrant() throws Exception {
        assertRefused("/topic/hosts/" + hostId + "/containers/web/logs");
        assertRefused("/topic/hosts/" + hostId + "/events");
    }

    @Test
    void hostGrantAllowsDeployOutput() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, 'VIEW')", userId, hostId);
        assertAllowed("/topic/deployments/" + registerRun());
    }

    private String registerHostRun() {
        String runId = UUID.randomUUID().toString();
        streams.registerRun(runId, hostId, null);
        streams.start(runId);
        return runId;
    }

    @Test
    void imagePullOutputNeedsTheWholeHost() throws Exception {
        assertRefused("/topic/deployments/" + registerHostRun());
    }

    @Test
    void imagePullOutputIsRefusedWithDeploymentGrantOnly() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) VALUES (?, ?, 'VIEW')", userId, deploymentId);
        assertRefused("/topic/deployments/" + registerHostRun());
    }

    @Test
    void hostGrantAllowsImagePullOutput() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, 'VIEW')", userId, hostId);
        assertAllowed("/topic/deployments/" + registerHostRun());
    }

    @Test
    void revokedHostGrantStopsLogAndEventSubscriptions() throws Exception {
        when(logService.follow(any(), any(dev.bethge.shipir.docker.DockerEndpoint.class), any(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(() -> {
                });
        when(eventService.stream(any(), any(dev.bethge.shipir.docker.DockerEndpoint.class), any(), any()))
                .thenReturn(() -> {
                });
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, 'VIEW')", userId, hostId);
        StompSession s = connect();
        subscribe(s, "/topic/hosts/" + hostId + "/containers/web/logs");
        subscribe(s, "/topic/hosts/" + hostId + "/events");
        var target = new LogStreamHandler.Target(hostId, "web");
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> logStreams.subscriberCount(target) == 1 && eventStreams.subscriberCount(hostId) == 1);

        // an ADMIN replaces the user's grants with none
        Map<String, String> adminJar = loginAs("streamadmin", Role.ADMIN);
        var r = callWith(adminJar, HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/users/" + userId + "/permissions"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", adminJar.get("XSRF-TOKEN"))
                .PUT(HttpRequest.BodyPublishers.ofString("{\"grants\":[]}")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);

        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> logStreams.subscriberCount(target) == 0 && eventStreams.subscriberCount(hostId) == 0);
        s.disconnect();
    }

    private Map<String, String> loginAs(String username, Role role) throws Exception {
        User u = users.findByUsername(username).orElseGet(() -> new User(username, "x", role));
        u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
        users.save(u);
        Map<String, String> j = new HashMap<>();
        callWith(j, HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me")));
        var r = callWith(j, HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", j.get("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}")));
        assertThat(r.statusCode()).isEqualTo(200);
        callWith(j, HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me")));
        return j;
    }

    private String deploymentLogs(String project) {
        return "/topic/hosts/" + hostId + "/deployments/" + project + "/logs";
    }

    @Test
    void deploymentLogsAreRefusedWithoutGrant() throws Exception {
        assertRefused(deploymentLogs("d"));
        verify(containerService, never()).listContainers(any(), any(dev.bethge.shipir.docker.DockerEndpoint.class));
    }

    @Test
    void deploymentLogsOfAManagedDeploymentNeedViewOfThatDeployment() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) VALUES (?, ?, 'VIEW')", userId, deploymentId);
        assertAllowed(deploymentLogs("d"));
        // a second managed deployment of the same host without grant stays hidden
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by) VALUES ('e', ?, 'x', ?)",
                hostId, userId);
        assertRefused(deploymentLogs("e"));
    }

    @Test
    void deploymentLogsOfAnUnmanagedProjectNeedTheWholeHost() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) VALUES (?, ?, 'VIEW')", userId, deploymentId);
        assertRefused(deploymentLogs("not-managed"));
        verify(containerService, never()).listContainers(any(), any(dev.bethge.shipir.docker.DockerEndpoint.class));
    }

    @Test
    void hostGrantAllowsDeploymentLogsOfAnyProject() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, 'VIEW')", userId, hostId);
        assertAllowed(deploymentLogs("not-managed"));
        assertAllowed(deploymentLogs("d"));
    }

    private void assertRefused(String destination) throws Exception {
        StompSession s = connect();
        subscribe(s, destination);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
    }

    private void assertAllowed(String destination) throws Exception {
        StompSession s = connect();
        subscribe(s, destination);
        Thread.sleep(500);
        assertThat(s.isConnected()).isTrue();
        s.disconnect();
    }

    private void subscribe(StompSession s, String destination) {
        s.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
            }
        });
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
        return callWith(jar, b);
    }

    private HttpResponse<String> callWith(Map<String, String> jar, HttpRequest.Builder b) throws Exception {
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

    private void login() throws Exception {
        jar.clear();
        call(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me")));
        HttpResponse<String> r = call(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"streamop\",\"password\":\"" + PASSWORD + "\"}")));
        assertThat(r.statusCode()).isEqualTo(200);
    }
}
