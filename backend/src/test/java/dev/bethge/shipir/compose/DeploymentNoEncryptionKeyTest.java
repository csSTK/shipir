package dev.bethge.shipir.compose;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without {@code shipir.encryption-key} deployments work, but env variables cannot be stored. */
@TestPropertySource(properties = "shipir.encryption-key=")
class DeploymentNoEncryptionKeyTest extends DeploymentTestBase {

    @Test
    void envWithoutKeyIs409AndStoresNothing() throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("web", YAML, "[{\"key\":\"A\",\"value\":\"b\"}]")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENCRYPTION_KEY_MISSING"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
        assertThat(files.readCompose(hostId, "web")).isEmpty();
    }

    @Test
    void deploymentWithoutEnvWorksWithoutKey() throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("web", YAML, null)))
                .andExpect(status().isCreated());
    }
}
