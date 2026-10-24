package dev.bethge.shipir.registry;

import dev.bethge.shipir.compose.execution.RegistryAuthSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryAuthSourceAdapterTest extends RegistryTestBase {

    @Autowired
    RegistryAuthSource source;

    @Test
    void emptyWithoutCredentials() {
        assertThat(source.auths()).isEmpty();
    }

    @Test
    void returnsDecryptedCredentials() throws Exception {
        create(login("admin"), "GHCR", "ghcr.io");
        assertThat(source.auths()).singleElement().satisfies(a -> {
            assertThat(a.registryHost()).isEqualTo("ghcr.io");
            assertThat(a.username()).isEqualTo("robot");
            assertThat(a.secret()).isEqualTo(SECRET);
        });
    }
}
