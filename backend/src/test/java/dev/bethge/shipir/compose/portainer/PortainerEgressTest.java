package dev.bethge.shipir.compose.portainer;

import dev.bethge.shipir.common.net.EgressHttpClient;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Default-deny egress for the import: refused before any connection, with and without the private-range opt-in. */
class PortainerEgressTest {

    private static PortainerImportService service(boolean allowPrivate) {
        EgressPolicy policy = new EgressPolicy(new EgressProperties(allowPrivate), host -> {
            if (host.equals("rebind.example")) {
                return new InetAddress[]{InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 254})};
            }
            return InetAddress.getAllByName(host);
        });
        return new PortainerImportService(new PortainerClient(new EgressHttpClient(policy)), policy);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:9", "http://localhost:9", "http://10.0.0.5", "http://192.168.1.1",
            "http://[::1]:9", "http://169.254.169.254", "http://metadata.google.internal", "http://rebind.example"})
    void internalTargetsAreRefusedByDefault(String url) {
        assertThatThrownBy(() -> service(false).listStacks(url, "tok"))
                .isInstanceOfSatisfying(PortainerException.class, e -> {
                    assertThat(e.code()).isEqualTo("EGRESS_DENIED");
                    assertThat(e.getMessage()).doesNotContain("tok");
                });
        assertThatThrownBy(() -> service(false).fetchStack(url, "tok", 1))
                .isInstanceOfSatisfying(PortainerException.class, e -> assertThat(e.code()).isEqualTo("EGRESS_DENIED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://169.254.169.254", "http://metadata.google.internal", "http://rebind.example"})
    void metadataStaysRefusedEvenWithPrivateRangesAllowed(String url) {
        assertThatThrownBy(() -> service(true).listStacks(url, "tok"))
                .isInstanceOfSatisfying(PortainerException.class, e -> assertThat(e.code()).isEqualTo("EGRESS_DENIED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://portainer.example", "file:///etc/passwd", "gopher://x", "javascript:alert(1)", "portainer.example",
            "", "   ", "http://", "http://user:pw@portainer.example", "https://portainer.example/?a=1", "https://portainer.example/#f",
            "http://exa mple.com"})
    void nonHttpOrMalformedUrlsAreRefused(String url) {
        assertThatThrownBy(() -> service(true).listStacks(url, "tok"))
                .isInstanceOfSatisfying(PortainerException.class, e -> assertThat(e.code()).isEqualTo("INVALID_URL"));
    }

    @Test
    void nullUrlIsInvalid() {
        assertThatThrownBy(() -> service(true).listStacks(null, "tok"))
                .isInstanceOfSatisfying(PortainerException.class, e -> assertThat(e.code()).isEqualTo("INVALID_URL"));
    }
}
