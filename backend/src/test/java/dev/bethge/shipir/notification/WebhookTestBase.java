package dev.bethge.shipir.notification;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Webhook tests: encryption key configured, short delivery timeout, ADMIN has an environment (setup gate). */
@TestPropertySource(properties = {
        "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "shipir.notification.webhook-timeout=1s"})
abstract class WebhookTestBase extends NotificationTestBase {

    private final List<Stub> stubs = new ArrayList<>();

    @BeforeEach
    void resetWebhooks() {
        jdbc.update("DELETE FROM notification_webhooks");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Webhook host', 'LOCAL', id FROM users WHERE username = 'admin'");
    }

    @AfterEach
    void stopStubs() {
        stubs.forEach(s -> s.server.stop(0));
    }

    /** Local receiver recording every request body; {@code behavior} may sleep or set the status. */
    Stub stub(int status, long delayMillis) throws IOException {
        Stub s = new Stub();
        s.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        s.server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            s.bodies.add(body);
            s.contentTypes.add(String.valueOf(ex.getRequestHeaders().getFirst("Content-Type")));
            s.paths.add(ex.getRequestURI().toString());
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (status >= 300 && status < 400) {
                ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + s.redirectPort + "/followed");
            }
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        s.server.start();
        stubs.add(s);
        return s;
    }

    static class Stub {
        HttpServer server;
        int redirectPort;
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<String> contentTypes = new CopyOnWriteArrayList<>();
        final List<String> paths = new CopyOnWriteArrayList<>();

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }
    }

    String createJson(String name, String url, String events, boolean enabled) {
        return "{\"name\":\"%s\",\"url\":\"%s\",\"eventTypes\":%s,\"enabled\":%s}".formatted(name, url, events, enabled);
    }
}
