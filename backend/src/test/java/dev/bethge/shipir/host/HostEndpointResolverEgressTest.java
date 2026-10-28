package dev.bethge.shipir.host;

import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A stored TCP host is egress-checked every time it is dialled, not only when it was created. */
class HostEndpointResolverEgressTest {

    private final DockerHostConnectionManager connections = mock(DockerHostConnectionManager.class);
    private final SecretCipher cipher = mock(SecretCipher.class);

    private HostEndpointResolver resolver(boolean allowPrivate) {
        when(cipher.decrypt(any())).thenReturn("pem");
        return new HostEndpointResolver(connections, cipher, new EgressPolicy(new EgressProperties(allowPrivate)));
    }

    private static DockerHost tcp(String host) {
        return DockerHost.tcp("h", host, 2376, new byte[]{1}, new byte[]{2}, new byte[]{3}, UUID.randomUUID());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.5", "169.254.169.254", "0.0.0.0"})
    void deniedStoredAddressIsNotDialledAndLooksUnreachable(String address) {
        assertThatThrownBy(() -> resolver(false).endpointOf(tcp(address)))
                .isInstanceOf(DockerConnectionException.class)
                .satisfies(e -> assertThat(((DockerConnectionException) e).getCode())
                        .isEqualTo(DockerErrorCode.UNREACHABLE));
    }

    @Test
    void metadataAddressStaysDeniedWhenPrivateRangesAreAllowed() {
        assertThatThrownBy(() -> resolver(true).endpointOf(tcp("169.254.169.254")))
                .isInstanceOf(DockerConnectionException.class);
    }

    @Test
    void allowedAddressYieldsTcpEndpoint() {
        assertThat(resolver(true).endpointOf(tcp("127.0.0.1"))).isInstanceOf(DockerEndpoint.Tcp.class);
    }

    @Test
    void localHostIsNotChecked() {
        var local = new DockerEndpoint.Local("/var/run/docker.sock");
        HostEndpointResolver r = resolver(false);
        when(connections.localEndpoint()).thenReturn(local);

        assertThat(r.endpointOf(DockerHost.local("l", UUID.randomUUID()))).isSameAs(local);
    }
}
