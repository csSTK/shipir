package dev.bethge.shipir.docker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DockerEndpointTest {

    @Test
    void tcpToStringHidesPemMaterial() {
        var tls = new DockerEndpoint.TlsMaterial("CA-PEM-SECRET", "CERT-PEM-SECRET", "KEY-PEM-SECRET");
        var endpoint = new DockerEndpoint.Tcp("10.0.0.5", 2376, tls);

        assertThat(endpoint.toString()).contains("10.0.0.5").doesNotContain("PEM-SECRET");
        assertThat(tls.toString()).doesNotContain("PEM-SECRET");
    }
}
