package dev.bethge.shipir.streaming;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ContainerStatsService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ContainerStatsDto;
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
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real server and STOMP client with mocked Docker services: frames, per-user filtering, worker lifecycle, denial. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shipir.streaming.stats.interval=200ms")
@Import(PostgresTestConfiguration.class)
class ContainerStatsTopicTest {

    static final String PASSWORD = "A-test-Passw0rd-for-stats!";

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
    ContainerStatsStreamService stats;
    @MockitoBean
    ContainerService containerService;
    @MockitoBean
    ContainerStatsService statsService;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID hostId;
    private UUID adminlessUserId;

    private static ContainerDto container(String id, String project) {
        return new ContainerDto(id, id, "img", "running", "Up", null, Instant.now(), List.of(), project, null);
    }

    @BeforeEach
    void setUp() {
        adminlessUserId = user("statsviewer");
        hostId = hosts.save(DockerHost.local("stats-" + UUID.randomUUID(), adminlessUserId)).getId();
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by) VALUES ('web', ?, 'x', ?)",
                hostId, adminlessUserId);
        when(containerService.listContainers(eq(hostId), any(DockerEndpoint.class))).thenReturn(List.of(
                container("c-web", "web"), container("c-other", "other"), container("c-plain", null),
                new ContainerDto("c-off", "off", "img", "exited", "Exited", null, Instant.now(), List.of(), "web", null)));
        when(statsService.snapshot(eq(hostId), any(DockerEndpoint.class), anyString())).thenReturn(
                new ContainerStatsDto(Instant.now(), 12.5, 100, 1000, 10, 1, 2, 3, 4));
    }

    @AfterEach
    void cleanUp() {
        hosts.deleteById(hostId);
    }

    private UUID user(String name) {
        User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", Role.VIEWER));
        u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
        return users.save(u).getId();
    }

    private String topic() {
        return "/topic/hosts/" + hostId + "/stats";
    }

    @Test
    void hostGrantSeesAllRunningContainersWithAllFields() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, 'VIEW')", adminlessUserId, hostId);
        StompSession s = connect("statsviewer");
        BlockingQueue<String> q = subscribe(s, topic());
        String frame = q.poll(10, TimeUnit.SECONDS);
        assertThat(frame).contains("\"seq\":1").contains("\"containerId\":\"c-web\"")
                .contains("\"containerId\":\"c-other\"").contains("\"containerId\":\"c-plain\"")
                .contains("\"cpu\":12.5").contains("\"memUsed\":100").contains("\"memLimit\":1000")
                .contains("\"netRx\":1").contains("\"netTx\":2").contains("\"blkRead\":3").contains("\"blkWrite\":4")
                .doesNotContain("c-off").doesNotContain("\"code\"");
        assertThat(q.poll(10, TimeUnit.SECONDS)).contains("\"seq\":2");
        s.disconnect();
    }

    @Test
    void deploymentGrantOnlySeesContainersOfItsManagedDeployment() throws Exception {
        UUID deploymentId = jdbc.queryForObject("SELECT id FROM deployments WHERE host_id = ?", UUID.class, hostId);
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) VALUES (?, ?, 'VIEW')",
                adminlessUserId, deploymentId);
        StompSession s = connect("statsviewer");
        BlockingQueue<String> q = subscribe(s, topic());
        String frame = q.poll(10, TimeUnit.SECONDS);
        assertThat(frame).contains("\"containerId\":\"c-web\"")
                .doesNotContain("c-other").doesNotContain("c-plain");
        s.disconnect();
    }

    @Test
    void subscribeWithoutAnyGrantIsRefusedAndNothingIsSampled() throws Exception {
        StompSession s = connect("statsviewer");
        subscribe(s, topic());
        await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
        assertThat(stats.activeStreams()).isZero();
        verify(statsService, never()).snapshot(any(), any(DockerEndpoint.class), any());
    }

    @Test
    void workerIsSharedAndStopsWithLastSubscriber() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, 'VIEW')", adminlessUserId, hostId);
        StompSession s1 = connect("statsviewer");
        StompSession s2 = connect("statsviewer");
        subscribe(s1, topic());
        subscribe(s2, topic());
        await().atMost(5, TimeUnit.SECONDS).until(() -> stats.subscriberCount(hostId) == 2);
        assertThat(stats.activeStreams()).isEqualTo(1);

        s1.disconnect();
        await().atMost(5, TimeUnit.SECONDS).until(() -> stats.subscriberCount(hostId) == 1);
        assertThat(stats.activeStreams()).isEqualTo(1);
        s2.disconnect();
        await().atMost(5, TimeUnit.SECONDS).until(() -> stats.activeStreams() == 0);

        // the worker is really gone: no further sampling after it stopped
        Thread.sleep(500);
        var calls = org.mockito.Mockito.mockingDetails(containerService).getInvocations().size();
        Thread.sleep(700);
        assertThat(org.mockito.Mockito.mockingDetails(containerService).getInvocations().size()).isEqualTo(calls);
    }

    @Test
    void unreachableHostYieldsFrameWithCodeAndNoSamples() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, 'VIEW')", adminlessUserId, hostId);
        when(containerService.listContainers(eq(hostId), any(DockerEndpoint.class)))
                .thenThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        StompSession s = connect("statsviewer");
        BlockingQueue<String> q = subscribe(s, topic());
        assertThat(q.poll(10, TimeUnit.SECONDS)).contains("\"samples\":[]").contains("\"code\":\"UNREACHABLE\"");
        s.disconnect();
    }

    @Test
    void malformedStatsDestinationsAreRefused() throws Exception {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, 'VIEW')", adminlessUserId, hostId);
        for (String bad : new String[]{"/topic/hosts/not-a-uuid/stats", topic() + "/extra", topic() + "x"}) {
            StompSession s = connect("statsviewer");
            subscribe(s, bad);
            await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
        }
        assertThat(stats.activeStreams()).isZero();
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

    private StompSession connect(String username) throws Exception {
        Map<String, String> jar = login(username);
        WebSocketHttpHeaders h = new WebSocketHttpHeaders();
        h.add("Cookie", "JSESSIONID=" + jar.get("JSESSIONID") + "; XSRF-TOKEN=" + jar.get("XSRF-TOKEN"));
        StompHeaders connect = new StompHeaders();
        connect.add("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"));
        WebSocketStompClient c = new WebSocketStompClient(new StandardWebSocketClient());
        c.setMessageConverter(new RawBytesConverter());
        return c.connectAsync("ws://127.0.0.1:" + port + "/ws", h, connect, new StompSessionHandlerAdapter() {
        }).get(5, TimeUnit.SECONDS);
    }

    private Map<String, String> login(String username) throws Exception {
        Map<String, String> jar = new HashMap<>();
        RealServerLogin.login(http, jar, port, username, PASSWORD);
        return jar;
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
