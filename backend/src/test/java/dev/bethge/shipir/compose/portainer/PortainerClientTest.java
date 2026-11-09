package dev.bethge.shipir.compose.portainer;

import dev.bethge.shipir.common.net.EgressHttpClient;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortainerClientTest {

    private static final URI BASE = URI.create("https://portainer.example.com:9443");

    private PortainerClient clientFailing(IOException failure) throws IOException {
        EgressHttpClient http = mock(EgressHttpClient.class);
        when(http.send(any(), any(), any(), any(Duration.class))).thenThrow(failure);
        return new PortainerClient(http);
    }

    @Test
    void tlsHandshakeFailureHasItsOwnCodeAndHint() throws Exception {
        PortainerClient client = clientFailing(new SSLHandshakeException("PKIX path building failed"));
        assertThatThrownBy(() -> client.get(BASE, "tok", "/api/stacks"))
                .isInstanceOfSatisfying(PortainerException.class, e -> {
                    assertThat(e.code()).isEqualTo("PORTAINER_TLS_FAILED");
                    assertThat(e.status().value()).isEqualTo(502);
                    assertThat(e.getMessage()).contains("certificate").doesNotContain("PKIX").doesNotContain("tok");
                });
    }

    @Test
    void tlsFailureWrappedInAnotherExceptionIsStillRecognised() throws Exception {
        PortainerClient client = clientFailing(new IOException("wrapper", new SSLHandshakeException("x")));
        assertThatThrownBy(() -> client.get(BASE, "tok", "/api/stacks"))
                .isInstanceOfSatisfying(PortainerException.class, e -> assertThat(e.code()).isEqualTo("PORTAINER_TLS_FAILED"));
    }

    @Test
    void otherIoFailuresStayUnreachable() throws Exception {
        PortainerClient client = clientFailing(new IOException("connection refused"));
        assertThatThrownBy(() -> client.get(BASE, "tok", "/api/stacks"))
                .isInstanceOfSatisfying(PortainerException.class, e -> assertThat(e.code()).isEqualTo("PORTAINER_UNREACHABLE"));
    }
}
