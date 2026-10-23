package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerEndpoint.TlsMaterial;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.ConnectionTestResult;
import dev.bethge.shipir.docker.dto.DockerSystemInfoDto;
import dev.bethge.shipir.docker.tls.TestPki;
import dev.bethge.shipir.docker.tls.TestPki.Issued;
import dev.bethge.shipir.docker.tls.TestPki.KeyType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A real {@code docker:dind} daemon in TLS mode ({@code --tlsverify}) with certificates generated per run.
 * The server certificate is valid for the DNS name {@code localhost} only, not for 127.0.0.1.
 */
@Testcontainers(disabledWithoutDocker = false)
@ExtendWith(OutputCaptureExtension.class)
class DockerSystemServiceTlsIntegrationTest {

    private static GenericContainer<?> dind;
    private static Issued ca;
    private static int port;

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerSystemService service;

    @BeforeAll
    static void startDaemon() {
        ca = TestPki.ca("shipir-test-ca", KeyType.EC);
        Issued server = TestPki.server(ca, "dind", KeyType.RSA, List.of("localhost"), List.of());
        dind = new GenericContainer<>("docker:dind")
                .withPrivilegedMode(true)
                .withEnv("DOCKER_TLS_CERTDIR", "/certs")
                .withCopyToContainer(Transferable.of(ca.certPem().getBytes(StandardCharsets.UTF_8), 0644), "/certs/server/ca.pem")
                .withCopyToContainer(Transferable.of(server.certPem().getBytes(StandardCharsets.UTF_8), 0644), "/certs/server/cert.pem")
                .withCopyToContainer(Transferable.of(server.keyPkcs8Pem().getBytes(StandardCharsets.UTF_8), 0600), "/certs/server/key.pem")
                .withExposedPorts(2376)
                .waitingFor(Wait.forLogMessage(".*API listen on.*\\n", 1).withStartupTimeout(Duration.ofMinutes(2)));
        dind.start();
        port = dind.getMappedPort(2376);
    }

    @AfterAll
    static void stopDaemon() {
        if (dind != null) dind.stop();
    }

    private DockerSystemService service() {
        manager = new DockerHostConnectionManager(
                new DockerProperties("unix:///var/run/docker.sock", Duration.ofSeconds(3), Duration.ofSeconds(10)));
        service = new DockerSystemServiceImpl(manager);
        return service;
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.evict(hostId);
    }

    private static DockerEndpoint tcp(String host, TlsMaterial tls) {
        return new DockerEndpoint.Tcp(host, port, tls);
    }

    private static TlsMaterial material(Issued trustedCa, Issued client, boolean traditional) {
        return new TlsMaterial(trustedCa.certPem(), client.certPem(),
                traditional ? client.keyTraditionalPem() : client.keyPkcs8Pem());
    }

    @ParameterizedTest
    @CsvSource({"RSA,false", "RSA,true", "EC,false", "EC,true"})
    void pingAndInfoSucceedWithCorrectCaAndClientCert(KeyType type, boolean traditional) {
        Issued client = TestPki.client(ca, "shipir-client", type);
        DockerEndpoint endpoint = tcp("localhost", material(ca, client, traditional));

        ConnectionTestResult ping = service().ping(endpoint);
        DockerSystemInfoDto info = service.info(hostId, endpoint);

        assertThat(ping.ok()).as(String.valueOf(ping.message())).isTrue();
        assertThat(ping.dockerVersion()).isNotBlank();
        assertThat(info.dockerVersion()).isNotBlank();
        assertThat(info.cpus()).isPositive();
    }

    @Test
    void wrongCaYieldsTlsError() {
        Issued otherCa = TestPki.ca("other-ca", KeyType.RSA);
        Issued client = TestPki.client(ca, "shipir-client", KeyType.RSA);
        DockerEndpoint endpoint = tcp("localhost", material(otherCa, client, false));

        ConnectionTestResult ping = service().ping(endpoint);

        assertThat(ping.ok()).isFalse();
        assertThat(ping.errorCode()).isEqualTo(DockerErrorCode.TLS_ERROR);
        assertThatThrownBy(() -> service.info(hostId, endpoint))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.TLS_ERROR));
    }

    @Test
    void clientCertNotSignedByServerCaYieldsTlsError() {
        Issued otherCa = TestPki.ca("other-ca", KeyType.RSA);
        Issued rogueClient = TestPki.client(otherCa, "rogue-client", KeyType.RSA);
        DockerEndpoint endpoint = tcp("localhost", material(ca, rogueClient, false));

        ConnectionTestResult ping = service().ping(endpoint);

        assertThat(ping.ok()).isFalse();
        assertThat(ping.errorCode()).isEqualTo(DockerErrorCode.TLS_ERROR);
    }

    @Test
    void serverCertForDifferentHostnameYieldsTlsError() {
        Issued client = TestPki.client(ca, "shipir-client", KeyType.RSA);
        // the certificate is only valid for "localhost"
        DockerEndpoint endpoint = tcp("127.0.0.1", material(ca, client, false));

        ConnectionTestResult ping = service().ping(endpoint);

        assertThat(ping.ok()).isFalse();
        assertThat(ping.errorCode()).isEqualTo(DockerErrorCode.TLS_ERROR);
    }

    @Test
    void garbageClientKeyYieldsInvalidTlsMaterialWithoutLeakingPem(CapturedOutput output) {
        Issued client = TestPki.client(ca, "shipir-client", KeyType.RSA);
        String garbageKey = "-----BEGIN PRIVATE KEY-----\nQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=\n-----END PRIVATE KEY-----\n";
        DockerEndpoint endpoint = tcp("localhost", new TlsMaterial(ca.certPem(), client.certPem(), garbageKey));

        ConnectionTestResult ping = service().ping(endpoint);
        assertThatThrownBy(() -> service.info(hostId, endpoint))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_TLS_MATERIAL));

        assertThat(ping.ok()).isFalse();
        assertThat(ping.errorCode()).isEqualTo(DockerErrorCode.INVALID_TLS_MATERIAL);
        assertThat(output.getAll()).doesNotContain("PRIVATE KEY").doesNotContain("QUJDREVG")
                .doesNotContain(client.keyPkcs8Pem().lines().skip(1).findFirst().orElseThrow())
                .doesNotContain(client.certPem().lines().skip(1).findFirst().orElseThrow());
    }

    @Test
    void missingClientKeyYieldsInvalidTlsMaterial() {
        Issued client = TestPki.client(ca, "shipir-client", KeyType.RSA);
        DockerEndpoint endpoint = tcp("localhost", new TlsMaterial(ca.certPem(), client.certPem(), null));

        ConnectionTestResult ping = service().ping(endpoint);

        assertThat(ping.errorCode()).isEqualTo(DockerErrorCode.INVALID_TLS_MATERIAL);
    }

    @Test
    void successfulConnectionLogsNoPem(CapturedOutput output) {
        Issued client = TestPki.client(ca, "shipir-client", KeyType.EC);

        service().ping(tcp("localhost", material(ca, client, false)));

        assertThat(output.getAll()).doesNotContain("BEGIN").doesNotContain("PRIVATE KEY");
    }
}
