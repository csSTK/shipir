package dev.bethge.shipir.streaming;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerLogService;
import dev.bethge.shipir.docker.dto.LogLine;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import com.github.dockerjava.api.DockerClient;
import dev.bethge.shipir.docker.client.DockerEventService;
import org.junit.jupiter.api.Assumptions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.DockerClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.io.Closeable;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real server, STOMP client and local Docker daemon: a container created on the daemon shows up on the topic. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shipir.streaming.events.debounce=100ms")
@Import(PostgresTestConfiguration.class)
class DockerEventTopicTest {

    static final String PASSWORD = "A-test-Passw0rd-for-events!";
    private static final String IMAGE = "alpine:3.20";

    @DynamicPropertySource
    static void dockerSocket(DynamicPropertyRegistry registry) {
        registry.add("shipir.docker.local-socket",
                () -> "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath());
    }

    @LocalServerPort
    int port;
    @Autowired
    UserRepository users;
    @Autowired
    DockerHostRepository hosts;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    DockerEventStreamService events;
    @MockitoSpyBean
    DockerEventService eventService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> jar = new HashMap<>();
    private String csrf;
    private UUID hostId;
    private DockerClient docker;
    private String containerId;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon available");
        docker = DockerClientFactory.instance().client();
        try {
            docker.inspectImageCmd(IMAGE).exec();
        } catch (Exception notPresent) {
            docker.pullImageCmd(IMAGE).start().awaitCompletion();
        }
        User user = users.findByUsername("viewer").orElseGet(() -> new User("viewer", "x", Role.VIEWER));
        user.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
        user = users.save(user);
        hostId = hosts.save(DockerHost.local("events-" + UUID.randomUUID(), user.getId())).getId();
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, \'VIEW\')", user.getId(), hostId);
        login();
    }

    @AfterEach
    void cleanUp() {
        if (containerId != null) docker.removeContainerCmd(containerId).withForce(true).exec();
        if (hostId != null) hosts.deleteById(hostId);
    }

    private HttpResponse<String> call(HttpRequest.Builder b) throws Exception {
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

    private void login() throws Exception {
        call(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me")));
        HttpResponse<String> r = call(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"viewer\",\"password\":\"" + PASSWORD + "\"}")));
        assertThat(r.statusCode()).isEqualTo(200);
        csrf = jar.get("XSRF-TOKEN");
    }

    private WebSocketHttpHeaders handshakeHeaders() {
        WebSocketHttpHeaders h = new WebSocketHttpHeaders();
        h.add("Cookie", "JSESSIONID=" + jar.get("JSESSIONID") + "; XSRF-TOKEN=" + jar.get("XSRF-TOKEN"));
        return h;
    }

    private StompSession connect() throws Exception {
        WebSocketStompClient c = new WebSocketStompClient(new StandardWebSocketClient());
        c.setMessageConverter(new RawBytesConverter());
        StompHeaders connect = new StompHeaders();
        connect.add("X-XSRF-TOKEN", csrf);
        CompletableFuture<StompSession> f = c.connectAsync("ws://localhost:" + port + "/ws", handshakeHeaders(),
                connect, new StompSessionHandlerAdapter() {
                });
        return f.get(5, TimeUnit.SECONDS);
    }

    private String topic() {
        return "/topic/hosts/" + hostId + "/events";
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

    private String createContainer() {
        containerId = docker.createContainerCmd(IMAGE).withCmd("sleep", "300").exec().getId();
        return containerId;
    }

    @Test
    void creatingAContainerYieldsAnEventFrame() throws Exception {
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic());
        String status = q.poll(10, TimeUnit.SECONDS);
        assertThat(status).contains("\"type\":\"STATUS\"").contains("\"status\":\"CONNECTED\"");

        String id = createContainer();
        String frame = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String next = q.poll(1, TimeUnit.SECONDS);
            if (next != null && next.contains(id)) {
                frame = next;
                break;
            }
        }
        assertThat(frame).as("event frame for the new container").isNotNull()
                .contains("\"type\":\"EVENT\"").contains("\"action\":\"create\"")
                .contains("\"type\":\"CONTAINER\"");
        s.disconnect();
    }

    @Test
    void streamStartsWithFirstSubscriberAndStopsAfterLast() throws Exception {
        StompSession s1 = connect();
        StompSession s2 = connect();
        subscribe(s1, topic());
        subscribe(s2, topic());
        await().atMost(5, TimeUnit.SECONDS).until(() -> events.subscriberCount(hostId) == 2);
        verify(eventService, times(1)).stream(eq(hostId), any(DockerEndpoint.class), any(), any());

        s1.disconnect();
        await().atMost(5, TimeUnit.SECONDS).until(() -> events.subscriberCount(hostId) == 1);
        assertThat(events.activeStreams()).isEqualTo(1);
        s2.disconnect();
        await().atMost(5, TimeUnit.SECONDS).until(() -> events.activeStreams() == 0);
    }

    @Test
    void malformedEventDestinationsAreRefused() throws Exception {
        for (String bad : new String[]{
                "/topic/hosts/not-a-uuid/events",
                "/topic/hosts/" + hostId + "/events/extra",
                "/topic/hosts/" + hostId + "/event"}) {
            StompSession s = connect();
            subscribe(s, bad);
            await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
        }
        verify(eventService, never()).stream(any(), any(DockerEndpoint.class), any(), any());
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
