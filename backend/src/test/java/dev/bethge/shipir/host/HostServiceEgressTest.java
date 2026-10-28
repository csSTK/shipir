package dev.bethge.shipir.host;

import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.client.DockerSystemService;
import dev.bethge.shipir.docker.dto.ConnectionTestResult;
import dev.bethge.shipir.host.dto.ConnectionTestRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The TCP connection test is an SSRF sink and must not be a port scanner. */
class HostServiceEgressTest {

    private final DockerSystemService docker = mock(DockerSystemService.class);

    private HostService service(boolean allowPrivate) {
        return new HostService(null, null, null, docker, null, null,
                new EgressPolicy(new EgressProperties(allowPrivate)), null, null, null);
    }

    private static ConnectionTestRequest tcp(String host) {
        String[] pems = HostApiTestBase.pems();
        return new ConnectionTestRequest(ConnectionType.TCP, host, 2376, pems[0], pems[1], pems[2]);
    }

    @Test
    void internalHostIsNeverContactedByDefaultAndLooksLikeAnyUnreachableHost() {
        ConnectionTestResult result = service(false).testConnection(tcp("127.0.0.1"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(DockerErrorCode.UNREACHABLE);
        verify(docker, never()).ping(any());
    }

    @Test
    void metadataHostIsRefusedEvenWhenPrivateRangesAreAllowed() {
        ConnectionTestResult result = service(true).testConnection(tcp("169.254.169.254"));

        assertThat(result.errorCode()).isEqualTo(DockerErrorCode.UNREACHABLE);
        verify(docker, never()).ping(any());
    }

    @Test
    void privateHostIsContactedWhenPrivateRangesAreAllowed() {
        when(docker.ping(any(DockerEndpoint.class))).thenReturn(new ConnectionTestResult(true, "27", null, null));

        assertThat(service(true).testConnection(tcp("127.0.0.1")).ok()).isTrue();
    }

    @Test
    void timeoutIsReportedAsUnreachableSoOpenAndFilteredPortsLookAlike() {
        when(docker.ping(any(DockerEndpoint.class))).thenReturn(
                new ConnectionTestResult(false, null, DockerErrorCode.TIMEOUT, "The Docker daemon did not answer in time."));

        ConnectionTestResult result = service(true).testConnection(tcp("127.0.0.1"));

        assertThat(result.errorCode()).isEqualTo(DockerErrorCode.UNREACHABLE);
    }

    @ParameterizedTest
    @EnumSource(value = DockerErrorCode.class,
            names = {"TLS_ERROR", "PERMISSION_DENIED", "TIMEOUT", "INVALID_RESPONSE", "UNREACHABLE"})
    void everyFailureToAnAllowedTargetCollapsesToOneGenericCode(DockerErrorCode code) {
        when(docker.ping(any(DockerEndpoint.class))).thenReturn(
                new ConnectionTestResult(false, null, code, "detail that tells the port is open"));

        ConnectionTestResult result = service(true).testConnection(tcp("127.0.0.1"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(DockerErrorCode.UNREACHABLE);
        assertThat(result.message()).isEqualTo("The Docker daemon is not reachable.");
    }

    @Test
    void localSocketKeepsTheRealErrorCode() {
        when(docker.ping(any(DockerEndpoint.class))).thenReturn(
                new ConnectionTestResult(false, null, DockerErrorCode.PERMISSION_DENIED, "no access to the socket"));
        var connections = mock(DockerHostConnectionManager.class);
        when(connections.localEndpoint()).thenReturn(new DockerEndpoint.Local("/var/run/docker.sock"));
        var local = new HostService(null, null, null, docker, connections, null,
                new EgressPolicy(new EgressProperties(false)), null, null, null);

        ConnectionTestResult result = local.testConnection(
                new ConnectionTestRequest(ConnectionType.LOCAL, null, null, null, null, null));

        assertThat(result.errorCode()).isEqualTo(DockerErrorCode.PERMISSION_DENIED);
    }
}
