package dev.bethge.shipir.ai.settings;

import com.sun.net.httpserver.HttpServer;
import dev.bethge.shipir.common.net.EgressHttpClient;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The AI connection test is an SSRF sink: internal targets get no request (and never the API key). */
class AiConnectionCheckerEgressTest {

    private HttpServer server;
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> {
            authHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AiConnectionChecker checker(boolean allowPrivate) {
        return new AiConnectionChecker(new AiSettingsProperties(true, Duration.ofSeconds(2)),
                new EgressHttpClient(new EgressPolicy(new EgressProperties(allowPrivate)), Duration.ofSeconds(2)));
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Test
    void loopbackIsRefusedByDefaultAndTheKeyIsNeverSent() {
        assertThat(checker(false).check(AiProvider.OPENAI_COMPATIBLE, base(), "sk-secret"))
                .isEqualTo(AiConnectionChecker.UNREACHABLE);
        assertThat(authHeaders).isEmpty();
    }

    @Test
    void loopbackIsReachableWhenPrivateRangesAreAllowed() {
        assertThat(checker(true).check(AiProvider.OPENAI_COMPATIBLE, base(), "sk-secret"))
                .isEqualTo(AiConnectionChecker.OK);
        assertThat(authHeaders).containsExactly("Bearer sk-secret");
    }

    @Test
    void metadataEndpointIsRefusedEvenWhenPrivateRangesAreAllowed() {
        assertThat(checker(true).check(AiProvider.OPENAI_COMPATIBLE, "http://169.254.169.254/v1", "sk-secret"))
                .isEqualTo(AiConnectionChecker.UNREACHABLE);
    }
}
