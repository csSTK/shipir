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

/** Real server and STOMP client; Docker is replaced by a scripted {@link ContainerLogService}. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shipir.streaming.logs.max-line-length=1000")
@Import(PostgresTestConfiguration.class)
class LogStreamTest {

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
    org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    LogStreamHandler handler;
    @MockitoBean
    ContainerLogService logService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> jar = new HashMap<>();
    private String csrf;
    private UUID hostId;
    private User user;

    private Consumer<LogLine> onLine;
    private Consumer<Throwable> onEnd;
    private final Closeable closeable = mock(Closeable.class);
    private final AtomicInteger follows = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        user = users.findByUsername("viewer").orElseGet(() -> new User("viewer", "x", Role.VIEWER));
        user.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
        user = users.save(user);
        hostId = hosts.save(DockerHost.local("logs-" + UUID.randomUUID(), user.getId())).getId();
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, \'VIEW\')", user.getId(), hostId);
        when(logService.follow(any(), any(DockerEndpoint.class), any(), anyInt(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    follows.incrementAndGet();
                    onLine = inv.getArgument(5);
                    onEnd = inv.getArgument(6);
                    return closeable;
                });
        login();
    }

    @AfterEach
    void cleanUp() {
        hosts.deleteById(hostId);
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

    private String topic(String cid) {
        return "/topic/hosts/" + hostId + "/containers/" + cid + "/logs";
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

    private static LogLine line(String text) {
        return new LogLine(LogLine.Stream.STDOUT, text, null);
    }

    private void awaitFollowing(String cid, int subscribers) {
        var target = LogStreamHandler.parse(topic(cid));
        await().atMost(5, TimeUnit.SECONDS).until(() -> handler.subscriberCount(target) >= subscribers && onLine != null);
    }

    @Test
    void subscriberReceivesLines() throws Exception {
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("web"));
        awaitFollowing("web", 1);
        onLine.accept(line("hello"));
        onLine.accept(new LogLine(LogLine.Stream.STDERR, "oops", null));
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"seq\":1").contains("\"type\":\"LINE\"")
                .contains("\"stream\":\"STDOUT\"").contains("\"line\":\"hello\"");
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"seq\":2").contains("\"stream\":\"STDERR\"").contains("oops");
        s.disconnect();
    }

    @Test
    void secondSubscriberSharesTheStreamAndGetsRecentLines() throws Exception {
        StompSession s1 = connect();
        BlockingQueue<String> q1 = subscribe(s1, topic("shared"));
        awaitFollowing("shared", 1);
        onLine.accept(line("early"));
        assertThat(q1.poll(5, TimeUnit.SECONDS)).contains("early");

        StompSession s2 = connect();
        BlockingQueue<String> q2 = subscribe(s2, topic("shared"));
        assertThat(q2.poll(5, TimeUnit.SECONDS)).contains("early");
        onLine.accept(line("later"));
        assertThat(q1.poll(5, TimeUnit.SECONDS)).contains("later");
        assertThat(q2.poll(5, TimeUnit.SECONDS)).contains("later");
        assertThat(follows.get()).isEqualTo(1);
        s1.disconnect();
        s2.disconnect();
    }

    @Test
    void followStopsOnlyAfterLastSubscriberLeaves() throws Exception {
        StompSession s1 = connect();
        StompSession s2 = connect();
        subscribe(s1, topic("refs"));
        subscribe(s2, topic("refs"));
        awaitFollowing("refs", 2);

        s1.disconnect();
        var target = LogStreamHandler.parse(topic("refs"));
        await().atMost(5, TimeUnit.SECONDS).until(() -> handler.subscriberCount(target) == 1);
        verify(closeable, never()).close();

        s2.disconnect();
        await().atMost(5, TimeUnit.SECONDS).until(() -> handler.subscriberCount(target) == 0);
        verify(closeable, times(1)).close();
    }

    @Test
    void unsubscribeOfLastSubscriptionStopsFollowAndResubscribeRestarts() throws Exception {
        StompSession s = connect();
        var sub = s.subscribe(topic("again"), new StompSessionHandlerAdapter() {
        });
        awaitFollowing("again", 1);
        sub.unsubscribe();
        var target = LogStreamHandler.parse(topic("again"));
        await().atMost(5, TimeUnit.SECONDS).until(() -> handler.subscriberCount(target) == 0);
        verify(closeable, atLeastOnce()).close();

        subscribe(s, topic("again"));
        await().atMost(5, TimeUnit.SECONDS).until(() -> follows.get() == 2);
        s.disconnect();
    }

    @Test
    void containerExitSendsStatusEnded() throws Exception {
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("exits"));
        awaitFollowing("exits", 1);
        onLine.accept(line("bye"));
        onEnd.accept(null);
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("bye");
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"type\":\"STATUS\"").contains("\"status\":\"ENDED\"");
        onLine.accept(line("after end"));
        assertThat(q.poll(500, TimeUnit.MILLISECONDS)).isNull();
        s.disconnect();
    }

    @Test
    void unknownContainerSendsStatusError() throws Exception {
        when(logService.follow(any(), any(DockerEndpoint.class), eq("ghost"), anyInt(), anyBoolean(), any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "no such container", null));
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("ghost"));
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"status\":\"ERROR\"").contains("\"line\":\"NOT_FOUND\"");
        s.disconnect();
    }

    @Test
    void unknownHostIsRefusedLikeAnInvisibleOne() throws Exception {
        StompSession s = connect();
        subscribe(s, "/topic/hosts/" + UUID.randomUUID() + "/containers/web/logs");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
    }

    @Test
    void longLinesAreCut() throws Exception {
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("long"));
        awaitFollowing("long", 1);
        onLine.accept(line("x".repeat(5000)));
        String frame = q.poll(5, TimeUnit.SECONDS);
        assertThat(frame).contains("[truncated]");
        assertThat(frame.length()).isLessThan(1500);
        s.disconnect();
    }

    @Test
    void unauthenticatedHandshakeIsRefused() {
        WebSocketStompClient c = new WebSocketStompClient(new StandardWebSocketClient());
        assertThatThrownBy(() -> c.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                new StompHeaders(), new StompSessionHandlerAdapter() {
                }).get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void malformedLogDestinationsAreRefused() throws Exception {
        for (String bad : new String[]{
                "/topic/hosts/not-a-uuid/containers/web/logs",
                "/topic/hosts/" + hostId + "/containers/../logs",
                "/topic/hosts/" + hostId + "/containers/web/logs/extra",
                "/topic/hosts/" + hostId + "/containers/web"}) {
            StompSession s = connect();
            subscribe(s, bad);
            await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
        }
        verify(logService, never()).follow(any(), any(DockerEndpoint.class), any(), anyInt(), anyBoolean(), any(), any());
    }

    private BlockingQueue<String> subscribe(StompSession s, String destination, String... headers) {
        StompHeaders h = new StompHeaders();
        h.setDestination(destination);
        for (int i = 0; i < headers.length; i += 2) {
            h.add(headers[i], headers[i + 1]);
        }
        BlockingQueue<String> q = new LinkedBlockingQueue<>();
        s.subscribe(h, new StompFrameHandler() {
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

    @Test
    void tailHeaderIsPassedToDocker() throws Exception {
        StompSession s = connect();
        subscribe(s, topic("tail500"), "tail", "500");
        await().atMost(5, TimeUnit.SECONDS).until(() -> follows.get() == 1);
        verify(logService).follow(any(), any(DockerEndpoint.class), eq("tail500"), eq(500), eq(false), any(), any());
        subscribe(s, topic("tailall"), "tail", "all");
        await().atMost(5, TimeUnit.SECONDS).until(() -> follows.get() == 2);
        verify(logService).follow(any(), any(DockerEndpoint.class), eq("tailall"), eq(-1), eq(false), any(), any());
        s.disconnect();
    }

    @Test
    void defaultTailComesFromProperties() throws Exception {
        StompSession s = connect();
        subscribe(s, topic("deftail"));
        await().atMost(5, TimeUnit.SECONDS).until(() -> follows.get() == 1);
        verify(logService).follow(any(), any(DockerEndpoint.class), eq("deftail"), eq(200), eq(false), any(), any());
        s.disconnect();
    }

    @Test
    void invalidOptionsAreRefusedWithoutFollow() throws Exception {
        for (String[] bad : new String[][]{{"tail", "50"}, {"tail", "-1"}, {"tail", "abc"}, {"timestamps", "yes"}}) {
            StompSession s = connect();
            subscribe(s, topic("badopt"), bad);
            await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
        }
        verify(logService, never()).follow(any(), any(DockerEndpoint.class), any(), anyInt(), anyBoolean(), any(), any());
    }

    @Test
    void timestampsAreOnlyInFramesWhenRequested() throws Exception {
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("ts"), "timestamps", "true");
        awaitFollowing("ts", 1);
        verify(logService).follow(any(), any(DockerEndpoint.class), eq("ts"), eq(200), eq(true), any(), any());
        onLine.accept(new LogLine(LogLine.Stream.STDOUT, "stamped", Instant.parse("2026-01-02T03:04:05.123Z")));
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"timestamp\":\"2026-01-02T03:04:05.123Z\"");
        s.disconnect();

        StompSession plain = connect();
        BlockingQueue<String> pq = subscribe(plain, topic("nots"));
        awaitFollowing("nots", 1);
        onLine.accept(line("plain"));
        assertThat(pq.poll(5, TimeUnit.SECONDS)).doesNotContain("timestamp");
        plain.disconnect();
    }

    @Test
    void sameOptionsShareOneFollowDifferentOptionsGetOwn() throws Exception {
        StompSession a = connect();
        StompSession b = connect();
        StompSession c = connect();
        subscribe(a, topic("opts"), "tail", "100");
        subscribe(b, topic("opts"), "tail", "100");
        var target = LogStreamHandler.parse(topic("opts"));
        await().atMost(5, TimeUnit.SECONDS).until(() -> handler.subscriberCount(target) == 2);
        assertThat(follows.get()).isEqualTo(1);
        subscribe(c, topic("opts"), "tail", "100", "timestamps", "true");
        await().atMost(5, TimeUnit.SECONDS).until(() -> follows.get() == 2);
        assertThat(handler.activeStreams()).isEqualTo(2);

        a.disconnect();
        b.disconnect();
        await().atMost(5, TimeUnit.SECONDS).until(() -> handler.subscriberCount(target) == 1);
        verify(closeable, times(1)).close();
        c.disconnect();
        await().atMost(5, TimeUnit.SECONDS).until(() -> handler.activeStreams() == 0);
        verify(closeable, times(2)).close();
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
