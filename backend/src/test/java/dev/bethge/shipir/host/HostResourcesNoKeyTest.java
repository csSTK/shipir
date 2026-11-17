package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TCP host exists but no encryption key is configured: ENCRYPTION_KEY_MISSING. */
@TestPropertySource(properties = "shipir.encryption-key=")
class HostResourcesNoKeyTest extends HostApiTestBase {

    @Test
    void missingEncryptionKeyIs502() throws Exception {
        UUID owner = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", UUID.class);
        jdbc.update("""
                INSERT INTO docker_hosts (name, connection_type, host, port, tls_ca_encrypted, tls_cert_encrypted,
                    tls_key_encrypted, owner_user_id) VALUES ('t', 'TCP', '203.0.113.6', 2376, ?, ?, ?, ?)""",
                new byte[]{1}, new byte[]{1}, new byte[]{1}, owner);
        String id = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 't'", UUID.class).toString();
        mvc.perform(get("/api/hosts/{id}/images", id).session(login("viewer").session()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ENCRYPTION_KEY_MISSING"));
    }
}
