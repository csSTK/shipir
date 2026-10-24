package dev.bethge.shipir.registry;

import dev.bethge.shipir.compose.execution.RegistryAuthSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "shipir.encryption-key=")
class RegistryAuthSourceAdapterWithoutKeyTest extends RegistryTestBase {

    @Autowired
    RegistryAuthSource source;

    @Test
    void rowsWithoutKeyYieldEmptyInsteadOfFailing() {
        jdbc.update("INSERT INTO registry_credentials (name, registry_host, username, secret_ciphertext, secret_hint, created_by) "
                + "SELECT 'X', 'ghcr.io', 'robot', '\\x0102'::bytea, 'hint', id FROM users WHERE username = 'admin'");
        assertThat(source.auths()).isEmpty();
    }
}
