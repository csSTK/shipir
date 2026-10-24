package dev.bethge.shipir.registry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryHostTest {

    @Test
    void normalisesCaseAndDockerHubAliases() {
        assertThat(RegistryHost.normalize("  GHCR.io ")).isEqualTo("ghcr.io");
        assertThat(RegistryHost.normalize("registry.example.com:5000")).isEqualTo("registry.example.com:5000");
        for (String alias : new String[] {"docker.io", "index.docker.io", "registry-1.docker.io"}) {
            assertThat(RegistryHost.normalize(alias)).isEqualTo("docker.io");
        }
        assertThat(RegistryHost.apiHost("docker.io")).isEqualTo("registry-1.docker.io");
        assertThat(RegistryHost.apiHost("ghcr.io")).isEqualTo("ghcr.io");
    }

    @Test
    void rejectsSchemePathUserinfoAndBadPorts() {
        for (String bad : new String[] {null, "", "http://a.io", "a.io/x", "u@a.io", "a.io:0", "a.io:65536",
                "a b", "a.io:12345678"}) {
            assertThatThrownBy(() -> RegistryHost.normalize(bad)).as(String.valueOf(bad))
                    .isInstanceOf(RegistryException.class).hasMessageContaining("hostname");
        }
    }
}
