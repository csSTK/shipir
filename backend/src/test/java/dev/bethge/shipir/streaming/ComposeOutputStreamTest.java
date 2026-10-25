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
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real server, real STOMP client over WebSocket with the session cookie from a normal login. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class ComposeOutputStreamTest {

    static final String PASSWORD = "A-test-Passw0rd-for-streams!";

    @LocalServerPort
    int port;
    @Autowired
    ComposeOutputStreamService streams;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    dev.bethge.shipir.host.DockerHostRepository hosts;

    private final HttpClient http = HttpClient.newHttpClient();
    private final java.util.Map<String, String> jar = new java.util.HashMap<>();
    private String csrf;

    @BeforeEach
    void user() {
        User u = users.findByUsername("streamadmin").orElseGet(() -> new User("streamadmin", "x", Role.ADMIN));
        u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
        var admin = users.save(u);
        // an ADMIN without any host is still at the setup gate's ENVIRONMENT step and gets no WebSocket
        if (hosts.count() == 0) {
            hosts.save(dev.bethge.shipir.host.DockerHost.local("stream-env", admin.getId()));
        }
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

    private void login() throws Exception {
        call(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me")));
        HttpResponse<String> r = call(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"streamadmin\",\"password\":\"" + PASSWORD + "\"}")));
        assertThat(r.statusCode()).isEqualTo(200);
        csrf = cookie("XSRF-TOKEN");
    }

    private String cookie(String name) {
        return jar.get(name);
    }

    private WebSocketHttpHeaders handshakeHeaders() {
        WebSocketHttpHeaders h = new WebSocketHttpHeaders();
        h.add("Cookie", "JSESSIONID=" + cookie("JSESSIONID") + "; XSRF-TOKEN=" + cookie("XSRF-TOKEN"));
        return h;
    }

    private WebSocketStompClient client() {
        WebSocketStompClient c = new WebSocketStompClient(new StandardWebSocketClient());
        c.setMessageConverter(new RawBytesConverter());
        return c;
    }

    private StompSession connect(WebSocketHttpHeaders headers, String csrfHeader) throws Exception {
        StompHeaders connect = new StompHeaders();
        if (csrfHeader != null) {
            connect.add("X-XSRF-TOKEN", csrfHeader);
        }
        CompletableFuture<StompSession> f = client().connectAsync("ws://localhost:" + port + "/ws", headers, connect,
                new StompSessionHandlerAdapter() {
                });
        return f.get(5, TimeUnit.SECONDS);
    }

    private BlockingQueue<String> subscribe(StompSession s, String id) {
        BlockingQueue<String> q = new LinkedBlockingQueue<>();
        s.subscribe("/topic/deployments/" + id, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                q.add(new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        return q;
    }

    @Test
    void rejectsUnauthenticatedHandshake() {
        assertThatThrownBy(() -> connect(new WebSocketHttpHeaders(), "x"))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void rejectsConnectWithoutCsrfToken() throws Exception {
        login();
        assertThatThrownBy(() -> connect(handshakeHeaders(), null)).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> connect(handshakeHeaders(), "wrong")).isInstanceOf(ExecutionException.class);
    }

    @Test
    void streamsLinesAndFinalStatusToAuthenticatedSubscriber() throws Exception {
        login();
        String id = UUID.randomUUID().toString();
        var sink = streams.start(id);
        StompSession s = connect(handshakeHeaders(), csrf);
        BlockingQueue<String> q = subscribe(s, id);
        // wait for the subscription to be registered before emitting
        awaitSubscribed(id);
        sink.accept("Container web  Started");
        streams.complete(id, RunStatus.DONE);
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"type\":\"LINE\"").contains("Container web  Started");
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"type\":\"STATUS\"").contains("\"status\":\"DONE\"");
        s.disconnect();
    }

    @org.junit.jupiter.api.RepeatedTest(20)
    void framesArriveInStrictSeqOrder() throws Exception {
        login();
        String id = UUID.randomUUID().toString();
        var sink = streams.start(id);
        StompSession s = connect(handshakeHeaders(), csrf);
        BlockingQueue<String> q = subscribe(s, id);
        awaitSubscribed(id);
        for (int i = 1; i <= 300; i++) {
            sink.accept("l" + i);
        }
        streams.complete(id, RunStatus.DONE);
        for (int i = 1; i <= 301; i++) {
            assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"seq\":" + i + ",");
        }
        s.disconnect();
    }

    @Test
    void failedStatusIsStreamed() throws Exception {
        login();
        String id = UUID.randomUUID().toString();
        streams.start(id);
        StompSession s = connect(handshakeHeaders(), csrf);
        BlockingQueue<String> q = subscribe(s, id);
        awaitSubscribed(id);
        streams.complete(id, RunStatus.FAILED);
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("\"status\":\"FAILED\"");
        s.disconnect();
    }

    @Test
    void lateSubscriberGetsBufferedLinesAndStatusInOrder() throws Exception {
        login();
        String id = UUID.randomUUID().toString();
        var sink = streams.start(id);
        sink.accept("one");
        sink.accept("two");
        streams.complete(id, RunStatus.DONE);
        sink.accept("ignored after completion");
        StompSession s = connect(handshakeHeaders(), csrf);
        BlockingQueue<String> q = subscribe(s, id);
        List<String> got = List.of(q.poll(5, TimeUnit.SECONDS), q.poll(5, TimeUnit.SECONDS), q.poll(5, TimeUnit.SECONDS));
        assertThat(got.get(0)).contains("\"seq\":1").contains("one");
        assertThat(got.get(1)).contains("\"seq\":2").contains("two");
        assertThat(got.get(2)).contains("STATUS").contains("DONE");
        assertThat(q.poll(500, TimeUnit.MILLISECONDS)).isNull();
        s.disconnect();
    }

    @Test
    void bufferKeepsOnlyNewestLines() throws Exception {
        login();
        String id = UUID.randomUUID().toString();
        var sink = streams.start(id);
        for (int i = 1; i <= 2500; i++) {
            sink.accept("line-" + i);
        }
        StompSession s = connect(handshakeHeaders(), csrf);
        BlockingQueue<String> q = subscribe(s, id);
        assertThat(q.poll(5, TimeUnit.SECONDS)).contains("line-501\"");
        s.disconnect();
    }

    @Test
    void refusesSubscriptionOutsideDeploymentTopics() throws Exception {
        login();
        StompSession s = connect(handshakeHeaders(), csrf);
        BlockingQueue<String> q = new LinkedBlockingQueue<>();
        var errors = new LinkedBlockingQueue<Throwable>();
        s.setAutoReceipt(false);
        // a forbidden destination makes the server close the session with an ERROR frame
        CompletableFuture<Void> closed = new CompletableFuture<>();
        StompSession s2 = client().connectAsync("ws://localhost:" + port + "/ws", handshakeHeaders(), csrfHeaders(),
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        closed.complete(null);
                    }

                    @Override
                    public void handleException(StompSession session, org.springframework.messaging.simp.stomp.StompCommand command,
                                                StompHeaders headers, byte[] payload, Throwable exception) {
                        closed.complete(null);
                    }
                }).get(5, TimeUnit.SECONDS);
        s2.subscribe("/topic/other", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                q.add(new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        // the server answers with an ERROR frame and closes the connection
        org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !s2.isConnected());
        assertThat(q).isEmpty();
    }

    @Test
    void refusesClientSend() throws Exception {
        login();
        StompSession s = connect(handshakeHeaders(), csrf);
        s.send("/topic/deployments/abc", "forged".getBytes());
        org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
    }

    private void awaitSubscribed(String id) {
        org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> streams.subscriberCount(id) > 0);
    }

    private StompHeaders csrfHeaders() {
        StompHeaders h = new StompHeaders();
        h.add("X-XSRF-TOKEN", csrf);
        return h;
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
