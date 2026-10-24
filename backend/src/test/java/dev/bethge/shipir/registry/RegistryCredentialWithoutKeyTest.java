package dev.bethge.shipir.registry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "shipir.encryption-key=")
class RegistryCredentialWithoutKeyTest extends RegistryTestBase {

    @Autowired
    RegistryCredentialService service;

    @Test
    void createWithoutEncryptionKeyIsRejected() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(post("/api/registries")).contentType(APPLICATION_JSON)
                        .content(json("A", "ghcr.io", "u", SECRET)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENCRYPTION_KEY_MISSING"));
        mvc.perform(get("/api/registries").session(l.session())).andExpect(status().isOk());
    }

    @Test
    void decryptedCredentialsIsEmptyWithoutRows() {
        assertThat(service.decryptedCredentials()).isEmpty();
    }
}
