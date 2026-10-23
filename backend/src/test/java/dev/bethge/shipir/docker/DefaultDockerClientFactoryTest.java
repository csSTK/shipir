package dev.bethge.shipir.docker;

import dev.bethge.shipir.docker.DockerEndpoint.TlsMaterial;
import dev.bethge.shipir.docker.client.DockerSystemServiceImpl;
import dev.bethge.shipir.docker.dto.ConnectionTestResult;
import dev.bethge.shipir.docker.tls.TestPki;
import dev.bethge.shipir.docker.tls.TestPki.Issued;
import dev.bethge.shipir.docker.tls.TestPki.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class DefaultDockerClientFactoryTest {

    private final DockerHostConnectionManager manager = new DockerHostConnectionManager(
            new DockerProperties("unix:///var/run/docker.sock", Duration.ofSeconds(1), Duration.ofSeconds(2)));

    @Test
    void pingWithInvalidKeyReportsInvalidTlsMaterialAndLogsNoPem(CapturedOutput output) {
        Issued ca = TestPki.ca("ca", KeyType.RSA);
        Issued client = TestPki.client(ca, "client", KeyType.RSA);
        String garbageKey = "-----BEGIN PRIVATE KEY-----\nQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=\n-----END PRIVATE KEY-----\n";
        var endpoint = new DockerEndpoint.Tcp("localhost", 1, new TlsMaterial(ca.certPem(), client.certPem(), garbageKey));

        ConnectionTestResult result = new DockerSystemServiceImpl(manager).ping(endpoint);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(DockerErrorCode.INVALID_TLS_MATERIAL);
        assertThat(result.message()).doesNotContain("PRIVATE KEY").doesNotContain("QUJDREVG");
        assertThat(output.getAll()).doesNotContain("PRIVATE KEY").doesNotContain("QUJDREVG")
                .doesNotContain(client.certPem().lines().skip(1).findFirst().orElseThrow());
    }

    @Test
    void pingWithMissingKeyReportsInvalidTlsMaterial() {
        Issued ca = TestPki.ca("ca", KeyType.EC);
        Issued client = TestPki.client(ca, "client", KeyType.EC);
        var endpoint = new DockerEndpoint.Tcp("localhost", 1, new TlsMaterial(ca.certPem(), client.certPem(), null));

        ConnectionTestResult result = new DockerSystemServiceImpl(manager).ping(endpoint);

        assertThat(result.errorCode()).isEqualTo(DockerErrorCode.INVALID_TLS_MATERIAL);
    }
}
