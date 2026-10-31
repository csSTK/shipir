package dev.bethge.shipir.streaming;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerLogService;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.LogLine;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real server and STOMP client; Docker is replaced by a scripted {@link ContainerService} (project containers) and
 * {@link ContainerLogService} (one scripted follow per container).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shipir.streaming.deployment-logs.max-line-length=1000",
        "shipir.streaming.deployment-logs.max-containers=3",
        "shipir.streaming.deployment-logs.max-lines-per-second-per-container=5",
        "shipir.streaming.deployment-logs.max-lines-per-second=8",
        "shipir.streaming.deployment-logs.buffer-lines=4",
        "shipir.streaming.deployment-logs.refresh-interval=200ms"})
@Import({PostgresTestConfiguration.class, DeploymentLogStreamTest.FixedClockConfig.class})
@org.junit.jupiter.api.Timeout(60)
class DeploymentLogStreamTest {

    static final String PASSWORD = "A-test-Passw0rd-for-streams!";

    /** Test clock that only moves when the test says so, so per-second rate windows are deterministic. */
    static final class MutableClock extends Clock {
        volatile long millis = 1_000_000_000L;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
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
    DeploymentLogStreamService service;
    @Autowired
    MutableClock clock;
    @MockitoBean
    ContainerLogService logService;
    @MockitoBean
    ContainerService containerService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> jar = new HashMap<>();
    private String csrf;
    private UUID hostId;
    private User user;

    private final Map<String, Consumer<LogLine>> lineSinks = new ConcurrentHashMap<>();
    private final Map<String, Consumer<Throwable>> endSinks = new ConcurrentHashMap<>();
    private final Map<String, Closeable> closeables = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> follows = new ConcurrentHashMap<>();
    private final AtomicReference<List<ContainerDto>> containers = new AtomicReference<>();

    private static ContainerDto container(String id, String service, String project, String state) {
        return new ContainerDto(id, project + "-" + service + "-1", "img", state, state, null, Instant.now(),
                List.of(), project, service);
    }

    private int followsOf(String id) {
        return follows.getOrDefault(id, new AtomicInteger()).get();
    }

    @BeforeEach
    void setUp() throws Exception {
        clock.millis = 1_000_000_000L;
        user = users.findByUsername("viewer").orElseGet(() -> new User("viewer", "x", Role.VIEWER));
        user.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
        user = users.save(user);
        hostId = hosts.save(DockerHost.local("dlogs-" + UUID.randomUUID(), user.getId())).getId();
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, 'VIEW')", user.getId(), hostId);
        containers.set(List.of(container("c-web", "web", "shop", "running"), container("c-db", "db", "shop", "running"),
                container("c-other", "x", "other", "running")));
        when(containerService.listContainers(any(), any(DockerEndpoint.class))).thenAnswer(inv -> containers.get());
        when(logService.follow(any(), any(DockerEndpoint.class), any(), anyInt(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    String id = inv.getArgument(2);
                    follows.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
                    lineSinks.put(id, inv.getArgument(5));
                    endSinks.put(id, inv.getArgument(6));
                    return closeables.computeIfAbsent(id, k -> mock(Closeable.class));
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

    private StompSession connect() throws Exception {
        WebSocketHttpHeaders h = new WebSocketHttpHeaders();
        h.add("Cookie", "JSESSIONID=" + jar.get("JSESSIONID") + "; XSRF-TOKEN=" + jar.get("XSRF-TOKEN"));
        WebSocketStompClient c = new WebSocketStompClient(new StandardWebSocketClient());
        c.setMessageConverter(new RawBytesConverter());
        StompHeaders connect = new StompHeaders();
        connect.add("X-XSRF-TOKEN", csrf);
        return c.connectAsync("ws://localhost:" + port + "/ws", h, connect, new StompSessionHandlerAdapter() {
        }).get(5, TimeUnit.SECONDS);
    }

    private String topic(String project) {
        return "/topic/hosts/" + hostId + "/deployments/" + project + "/logs";
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

    private static LogLine line(String text) {
        return new LogLine(LogLine.Stream.STDOUT, text, null);
    }

    private long listings() {
        return org.mockito.Mockito.mockingDetails(containerService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("listContainers")).count();
    }

    /**
     * Waits until {@code rounds} refresh rounds have completed. A round starts with one container listing and the worker
     * runs rounds one after the other, so the listing of round n+1 proves round n (follow attempts included) is done.
     */
    private void awaitRefreshRounds(int rounds) {
        long target = listings() + rounds + 1;
        await().atMost(10, TimeUnit.SECONDS).until(() -> listings() >= target);
    }

    private void awaitFollowing(String... ids) {
        await().atMost(5, TimeUnit.SECONDS).until(() -> {
            for (String id : ids) {
                if (!lineSinks.containsKey(id)) {
                    return false;
                }
            }
            return true;
        });
    }

    private List<String> drain(BlockingQueue<String> q, long quietMillis) throws Exception {
        List<String> out = new ArrayList<>();
        String f;
        // hard deadline: a producer that never goes quiet must make the caller's assertion fail, not hang the test
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(quietMillis * 10, 5000));
        while (System.nanoTime() < deadline && (f = q.poll(quietMillis, TimeUnit.MILLISECONDS)) != null) {
            out.add(f);
        }
        return out;
    }

    @Test
    void mergesContainersOfTheProjectWithServiceNames() throws Exception {
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("shop"));
        awaitFollowing("c-web", "c-db");
        lineSinks.get("c-web").accept(line("from web"));
        lineSinks.get("c-db").accept(new LogLine(LogLine.Stream.STDERR, "from db", null));
        String first = q.poll(5, TimeUnit.SECONDS);
        assertThat(first).contains("\"seq\":1").contains("\"type\":\"LINE\"").contains("\"service\":\"web\"")
                .contains("\"container\":\"shop-web-1\"").contains("\"containerId\":\"c-web\"")
                .contains("\"stream\":\"STDOUT\"").contains("\"line\":\"from web\"");
        String second = q.poll(5, TimeUnit.SECONDS);
        assertThat(second).contains("\"seq\":2").contains("\"service\":\"db\"").contains("\"stream\":\"STDERR\"")
                .contains("from db");
        s.disconnect();
    }

    @Test
    void containersOfOtherProjectsAreNotFollowed() throws Exception {
        StompSession s = connect();
        subscribe(s, topic("shop"));
        awaitFollowing("c-web", "c-db");
        verify(logService, never()).follow(any(), any(DockerEndpoint.class), eq("c-other"), anyInt(), anyBoolean(),
                any(), any());
        s.disconnect();
    }

    @Test
    void secondSubscriberSharesFollowersAndGetsRecentLines() throws Exception {
        StompSession s1 = connect();
        BlockingQueue<String> q1 = subscribe(s1, topic("shop"));
        awaitFollowing("c-web", "c-db");
        lineSinks.get("c-web").accept(line("early"));
        assertThat(q1.poll(5, TimeUnit.SECONDS)).contains("early");

        StompSession s2 = connect();
        BlockingQueue<String> q2 = subscribe(s2, topic("shop"));
        assertThat(q2.poll(5, TimeUnit.SECONDS)).contains("early").contains("\"service\":\"web\"");
        lineSinks.get("c-db").accept(line("later"));
        assertThat(q1.poll(5, TimeUnit.SECONDS)).contains("later");
        assertThat(q2.poll(5, TimeUnit.SECONDS)).contains("later");
        assertThat(followsOf("c-web")).isEqualTo(1);
        assertThat(followsOf("c-db")).isEqualTo(1);
        s1.disconnect();
        s2.disconnect();
    }

    @Test
    void replayBufferIsBounded() throws Exception {
        StompSession s1 = connect();
        BlockingQueue<String> q1 = subscribe(s1, topic("shop"));
        awaitFollowing("c-web");
        for (int i = 0; i < 4; i++) {
            lineSinks.get("c-web").accept(line("old" + i));
        }
        clock.millis += 1000;
        for (int i = 4; i < 8; i++) {
            lineSinks.get("c-web").accept(line("old" + i));
        }
        assertThat(drain(q1, 700)).hasSize(8);

        StompSession s2 = connect();
        BlockingQueue<String> q2 = subscribe(s2, topic("shop"));
        List<String> replay = drain(q2, 700);
        assertThat(replay).hasSize(4);
        assertThat(replay.get(0)).contains("old4");
        s1.disconnect();
        s2.disconnect();
    }

    @Test
    void followersStopOnlyAfterLastSubscriberLeaves() throws Exception {
        StompSession s1 = connect();
        StompSession s2 = connect();
        subscribe(s1, topic("shop"));
        subscribe(s2, topic("shop"));
        awaitFollowing("c-web", "c-db");
        var target = DeploymentLogStreamService.parse(topic("shop"));
        await().atMost(5, TimeUnit.SECONDS).until(() -> service.subscriberCount(target) == 2);

        s1.disconnect();
        await().atMost(5, TimeUnit.SECONDS).until(() -> service.subscriberCount(target) == 1);
        verify(closeables.get("c-web"), never()).close();
        verify(closeables.get("c-db"), never()).close();

        s2.disconnect();
        await().atMost(5, TimeUnit.SECONDS).until(() -> service.subscriberCount(target) == 0);
        verify(closeables.get("c-web"), times(1)).close();
        verify(closeables.get("c-db"), times(1)).close();
    }

    @Test
    void noMoreRefreshingAfterTheLastSubscriberLeft() throws Exception {
        StompSession s = connect();
        subscribe(s, topic("shop"));
        awaitFollowing("c-web", "c-db");
        s.disconnect();
        var target = DeploymentLogStreamService.parse(topic("shop"));
        await().atMost(5, TimeUnit.SECONDS).until(() -> service.subscriberCount(target) == 0);
        // the stream is closed under its lock before it leaves the map, so no refresh can start a follow any more
        containers.set(List.of(container("c-late", "late", "shop", "running")));
        // inherently a negative check: a worker that kept refreshing (every 200ms) would follow c-late inside the window
        await().during(600, TimeUnit.MILLISECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(logService, never()).follow(any(), any(DockerEndpoint.class), eq("c-late"), anyInt(),
                        anyBoolean(), any(), any()));
    }

    @Test
    void tailAndTimestampsHeadersApplyToEveryContainer() throws Exception {
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("shop"), "tail", "500", "timestamps", "true");
        awaitFollowing("c-web", "c-db");
        verify(logService).follow(any(), any(DockerEndpoint.class), eq("c-web"), eq(500), eq(true), any(), any());
        verify(logService).follow(any(), any(DockerEndpoint.class), eq("c-db"), eq(500), eq(true), any(), any());
        lineSinks.get("c-web").accept(new LogLine(LogLine.Stream.STDOUT, "stamped", Instant.parse("2026-01-02T03:04:05.123Z")));
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"timestamp\":\"2026-01-02T03:04:05.123Z\"");
        s.disconnect();
    }

    @Test
    void defaultTailComesFromProperties() throws Exception {
        StompSession s = connect();
        subscribe(s, topic("shop"));
        awaitFollowing("c-web");
        verify(logService).follow(any(), any(DockerEndpoint.class), eq("c-web"), eq(100), eq(false), any(), any());
        s.disconnect();
    }

    @Test
    void invalidOptionsAreRefusedWithoutFollow() throws Exception {
        for (String[] bad : new String[][]{{"tail", "50"}, {"tail", "-1"}, {"tail", "abc"}, {"timestamps", "yes"}}) {
            StompSession s = connect();
            subscribe(s, topic("shop"), bad);
            await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
        }
        verify(logService, never()).follow(any(), any(DockerEndpoint.class), any(), anyInt(), anyBoolean(), any(), any());
    }

    @Test
    void malformedProjectDestinationsAreRefused() throws Exception {
        for (String bad : new String[]{
                "/topic/hosts/not-a-uuid/deployments/shop/logs",
                "/topic/hosts/" + hostId + "/deployments/../logs",
                "/topic/hosts/" + hostId + "/deployments/Shop/logs",
                "/topic/hosts/" + hostId + "/deployments/-shop/logs",
                "/topic/hosts/" + hostId + "/deployments/sh op/logs",
                "/topic/hosts/" + hostId + "/deployments/" + "a".repeat(64) + "/logs",
                "/topic/hosts/" + hostId + "/deployments/shop/logs/extra",
                "/topic/hosts/" + hostId + "/deployments/shop"}) {
            StompSession s = connect();
            subscribe(s, bad);
            await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
        }
        verify(logService, never()).follow(any(), any(DockerEndpoint.class), any(), anyInt(), anyBoolean(), any(), any());
    }

    @Test
    void unknownHostIsRefused() throws Exception {
        StompSession s = connect();
        subscribe(s, "/topic/hosts/" + UUID.randomUUID() + "/deployments/shop/logs");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
    }

    @Test
    void endedContainerIsReportedOthersKeepStreaming() throws Exception {
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("shop"));
        awaitFollowing("c-web", "c-db");
        endSinks.get("c-web").accept(null);
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"type\":\"STATUS\"").contains("\"status\":\"ENDED\"")
                .contains("\"service\":\"web\"");
        lineSinks.get("c-db").accept(line("still here"));
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("still here");
        s.disconnect();
    }

    @Test
    void failingContainerFollowIsReportedWithItsServiceAndOthersContinue() throws Exception {
        when(logService.follow(any(), any(DockerEndpoint.class), eq("c-db"), anyInt(), anyBoolean(), any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "gone", null));
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("shop"));
        awaitFollowing("c-web");
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"status\":\"ERROR\"").contains("\"line\":\"NOT_FOUND\"")
                .contains("\"service\":\"db\"");
        lineSinks.get("c-web").accept(line("alive"));
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("alive");
        s.disconnect();
    }

    @Test
    void failedFollowIsRetriedAndLinesFlowOnceItSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        when(logService.follow(any(), any(DockerEndpoint.class), eq("c-db"), anyInt(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null);
                    }
                    lineSinks.put("c-db", inv.getArgument(5));
                    endSinks.put("c-db", inv.getArgument(6));
                    return mock(Closeable.class);
                });
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("shop"));
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"status\":\"ERROR\"").contains("\"service\":\"db\"");
        awaitFollowing("c-db"); // picked up by a later refresh without a new SUBSCRIBE
        lineSinks.get("c-db").accept(line("back again"));
        assertThat(drain(q, 500)).anyMatch(f -> f.contains("back again") && f.contains("\"service\":\"db\""));
        assertThat(attempts.get()).isEqualTo(2);
        s.disconnect();
    }

    @Test
    void failedFollowIsRetriedOnlyAFewTimesAndErrorIsReportedOnce() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        when(logService.follow(any(), any(DockerEndpoint.class), eq("c-db"), anyInt(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    attempts.incrementAndGet();
                    throw new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null);
                });
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("shop"));
        await().atMost(10, TimeUnit.SECONDS).until(() -> attempts.get() >= 4);
        awaitRefreshRounds(10); // more rounds than the longest backoff (4 rounds) needs: no further attempt
        assertThat(attempts.get()).isEqualTo(4); // first try + 3 retries
        assertThat(drain(q, 300).stream().filter(f -> f.contains("\"service\":\"db\"")).count()).isEqualTo(1);
        s.disconnect();
    }

    @Test
    void containerWhoseRetriesWereExhaustedIsFollowedAgainAfterItRestarted() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        when(logService.follow(any(), any(DockerEndpoint.class), eq("c-db"), anyInt(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    if (attempts.incrementAndGet() <= 4) { // first try + 3 retries fail
                        throw new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null);
                    }
                    lineSinks.put("c-db", inv.getArgument(5));
                    endSinks.put("c-db", inv.getArgument(6));
                    return mock(Closeable.class);
                });
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("shop"));
        await().atMost(10, TimeUnit.SECONDS).until(() -> attempts.get() >= 4);
        awaitRefreshRounds(10);
        assertThat(attempts.get()).isEqualTo(4); // given up while it kept running

        containers.set(List.of(container("c-web", "web", "shop", "running"), container("c-db", "db", "shop", "exited")));
        awaitRefreshRounds(2);
        containers.set(List.of(container("c-web", "web", "shop", "running"), container("c-db", "db", "shop", "running")));
        awaitFollowing("c-db");
        lineSinks.get("c-db").accept(line("after restart"));
        assertThat(drain(q, 500)).anyMatch(f -> f.contains("after restart") && f.contains("\"service\":\"db\""));
        s.disconnect();
    }

    @Test
    void containerStartedLaterIsPickedUpByTheRefresh() throws Exception {
        containers.set(List.of(container("c-web", "web", "shop", "running")));
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("shop"));
        awaitFollowing("c-web");
        assertThat(lineSinks).doesNotContainKey("c-db");

        containers.set(List.of(container("c-web", "web", "shop", "running"), container("c-db", "db", "shop", "running")));
        awaitFollowing("c-db");
        lineSinks.get("c-db").accept(line("hello late"));
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("hello late").contains("\"service\":\"db\"");
        // the already followed container is not followed a second time
        assertThat(followsOf("c-web")).isEqualTo(1);
        s.disconnect();
    }

    @Test
    void restartedContainerIsFollowedAgainWithoutTail() throws Exception {
        StompSession s = connect();
        subscribe(s, topic("shop"));
        awaitFollowing("c-web", "c-db");
        containers.set(List.of(container("c-web", "web", "shop", "exited"), container("c-db", "db", "shop", "running")));
        endSinks.get("c-web").accept(null);
        awaitRefreshRounds(2);
        assertThat(followsOf("c-web")).isEqualTo(1); // not running: not followed again
        containers.set(List.of(container("c-web", "web", "shop", "running"), container("c-db", "db", "shop", "running")));
        await().atMost(5, TimeUnit.SECONDS).until(() -> followsOf("c-web") == 2);
        verify(logService).follow(any(), any(DockerEndpoint.class), eq("c-web"), eq(0), eq(false), any(), any());
        s.disconnect();
    }

    @Test
    void containersAboveTheLimitAreIgnoredAndReportedOnce() throws Exception {
        containers.set(List.of(container("c1", "a", "shop", "running"), container("c2", "b", "shop", "running"),
                container("c3", "c", "shop", "running"), container("c4", "d", "shop", "running")));
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("shop"));
        awaitFollowing("c1", "c2", "c3");
        awaitRefreshRounds(3);
        assertThat(lineSinks).doesNotContainKey("c4");
        List<String> frames = drain(q, 300);
        assertThat(frames.stream().filter(f -> f.contains("CONTAINER_LIMIT")).count()).isEqualTo(1);
        assertThat(frames.stream().filter(f -> f.contains("CONTAINER_LIMIT")).findFirst().orElseThrow())
                .contains("\"status\":\"ERROR\"").doesNotContain("\"service\"");
        s.disconnect();
    }

    @Test
    void perContainerRateCapDropsExcessLinesAndSummarizes() throws Exception {
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("shop"));
        awaitFollowing("c-web");
        for (int i = 0; i < 20; i++) {
            lineSinks.get("c-web").accept(line("spam" + i));
        }
        assertThat(drain(q, 500)).hasSize(5);
        clock.millis += 1000;
        lineSinks.get("c-web").accept(line("next second"));
        List<String> next = drain(q, 500);
        assertThat(next).hasSize(2);
        assertThat(next.get(0)).contains("15 lines skipped");
        assertThat(next.get(1)).contains("next second");
        s.disconnect();
    }

    @Test
    void overallRateCapAppliesAcrossContainers() throws Exception {
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("shop"));
        awaitFollowing("c-web", "c-db");
        for (int i = 0; i < 5; i++) {
            lineSinks.get("c-web").accept(line("w" + i));
            lineSinks.get("c-db").accept(line("d" + i));
        }
        // 10 lines offered, each container within its own cap of 5, the project cap is 8
        assertThat(drain(q, 500)).hasSize(8);
        s.disconnect();
    }

    @Test
    void longLinesAreCut() throws Exception {
        StompSession s = connect();
        BlockingQueue<String> q = subscribe(s, topic("shop"));
        awaitFollowing("c-web");
        lineSinks.get("c-web").accept(line("x".repeat(5000)));
        String frame = q.poll(5, TimeUnit.SECONDS);
        assertThat(frame).contains("[truncated]");
        assertThat(frame.length()).isLessThan(1500);
        s.disconnect();
    }

    @Test
    void subscribeAfterTheWorkerStoppedForGoodGetsAFreshWorker() throws Exception {
        when(containerService.listContainers(any(), any(DockerEndpoint.class)))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "gone", null));
        StompSession s1 = connect();
        BlockingQueue<String> q1 = subscribe(s1, topic("shop"));
        assertThat(q1.poll(5, TimeUnit.SECONDS)).contains("\"status\":\"ERROR\"").contains("NOT_FOUND");
        await().atMost(5, TimeUnit.SECONDS).until(() -> service.subscriberCount(DeploymentLogStreamService.parse(topic("shop"))) == 0); // the dead stream left the map

        when(containerService.listContainers(any(), any(DockerEndpoint.class))).thenAnswer(inv -> containers.get());
        StompSession s2 = connect();
        BlockingQueue<String> q2 = subscribe(s2, topic("shop"));
        awaitFollowing("c-web", "c-db");
        lineSinks.get("c-web").accept(line("back"));
        assertThat(q2.poll(5, TimeUnit.SECONDS)).contains("back");
        s1.disconnect();
        s2.disconnect();
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
