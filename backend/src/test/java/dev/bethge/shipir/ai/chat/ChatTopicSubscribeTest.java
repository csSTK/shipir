package dev.bethge.shipir.ai.chat;

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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real server and STOMP client: only the owner may follow {@code /topic/chat/{sessionId}}. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class ChatTopicSubscribeTest {

    static final String PASSWORD = "A-test-Passw0rd-for-chat-topic!";

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired dev.bethge.shipir.host.DockerHostRepository hosts;
    @Autowired ChatSessionService sessions;
    @Autowired ChatEventPublisher publisher;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler broker;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void users() {
        for (String name : new String[]{"viewer", "operator", "admin"}) {
            Role role = Role.valueOf(name.toUpperCase());
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            var saved = users.save(u);
            // an ADMIN without any host is still at the setup gate's ENVIRONMENT step and gets no WebSocket
            if (role == Role.ADMIN && hosts.count() == 0) {
                hosts.save(dev.bethge.shipir.host.DockerHost.local("chat-env", saved.getId()));
            }
        }
    }

    /** Logs in and returns the cookies of that browser session. */
    private Map<String, String> login(String username) throws Exception {
        Map<String, String> jar = new HashMap<>();
        call(jar, HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me")));
        call(jar, HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}")));
        return jar;
    }

    private void call(Map<String, String> jar, HttpRequest.Builder b) throws Exception {
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
    }

    private StompSession connect(Map<String, String> jar) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Cookie", "JSESSIONID=" + jar.get("JSESSIONID") + "; XSRF-TOKEN=" + jar.get("XSRF-TOKEN"));
        StompHeaders connect = new StompHeaders();
        connect.add("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"));
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new RawBytesConverter());
        return client.connectAsync("ws://localhost:" + port + "/ws", headers, connect,
                new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);
    }

    private BlockingQueue<String> subscribe(StompSession s, String sessionId) {
        BlockingQueue<String> q = new LinkedBlockingQueue<>();
        s.subscribe("/topic/chat/" + sessionId, new StompFrameHandler() {
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

    private UUID sessionOf(String username) {
        return sessions.create(users.findByUsername(username).orElseThrow().getId(), "Topic test").id();
    }

    @Test
    void ownerReceivesLiveEvents() throws Exception {
        UUID id = sessionOf("viewer");
        StompSession s = connect(login("viewer"));
        BlockingQueue<String> q = subscribe(s, id.toString());
        // publish until the subscription is live (the broker registers it asynchronously)
        await().atMost(5, TimeUnit.SECONDS).until(() -> {
            publisher.publish(id, ChatStreamEvent.token(UUID.randomUUID(), "Hel"));
            return !q.isEmpty();
        });
        assertThat(q.poll()).contains("\"type\":\"TOKEN\"").contains("Hel");
        s.disconnect();
    }

    @Test
    void disabledOwnerStopsReceivingLiveEvents() throws Exception {
        UUID id = sessionOf("viewer");
        StompSession s = connect(login("viewer"));
        BlockingQueue<String> q = subscribe(s, id.toString());
        await().atMost(5, TimeUnit.SECONDS).until(() -> {
            publisher.publish(id, ChatStreamEvent.token(UUID.randomUUID(), "Hel"));
            return !q.isEmpty();
        });
        User viewer = users.findByUsername("viewer").orElseThrow();
        viewer.disable(Instant.now());
        users.save(viewer);
        try {
            events.publishEvent(new dev.bethge.shipir.common.security.UserAccessChangedEvent("viewer"));
            // the revalidation removes the subscription from the broker: wait for that, then drain what was in flight
            var probe = org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(
                    org.springframework.messaging.simp.SimpMessageType.MESSAGE);
            probe.setDestination("/topic/chat/" + id);
            var message = org.springframework.messaging.support.MessageBuilder.createMessage(new byte[0], probe.getMessageHeaders());
            await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> broker.getSubscriptionRegistry().findSubscriptions(message).isEmpty());
            q.clear();
            publisher.publish(id, ChatStreamEvent.token(UUID.randomUUID(), "secret"));
            assertThat(q.poll(500, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            viewer.enable();
            users.save(viewer);
        }
        s.disconnect();
    }

    @Test
    void otherUserIsRefusedEvenAdmin() throws Exception {
        UUID id = sessionOf("viewer");
        for (String name : new String[]{"operator", "admin"}) {
            StompSession s = connect(login(name));
            BlockingQueue<String> q = subscribe(s, id.toString());
            // a refused SUBSCRIBE makes the server close the connection with an ERROR frame
            await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
            publisher.publish(id, ChatStreamEvent.token(UUID.randomUUID(), "secret"));
            assertThat(q.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void unknownOrMalformedSessionIdIsRefused() throws Exception {
        for (String target : new String[]{UUID.randomUUID().toString(), "not-a-uuid"}) {
            StompSession s = connect(login("viewer"));
            subscribe(s, target);
            await().atMost(5, TimeUnit.SECONDS).until(() -> !s.isConnected());
        }
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
