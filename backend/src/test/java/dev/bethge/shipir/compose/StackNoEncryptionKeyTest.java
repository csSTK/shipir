package dev.bethge.shipir.compose;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without {@code shipir.encryption-key} stacks work, but env variables cannot be stored. */
class StackNoEncryptionKeyTest extends StackTestBase {

    @Test
    void envWithoutKeyIs409AndStoresNothing() throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("web", YAML, "[{\"key\":\"A\",\"value\":\"b\"}]")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENCRYPTION_KEY_MISSING"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stacks", Integer.class)).isZero();
        assertThat(files.readCompose(hostId, "web")).isEmpty();
    }

    @Test
    void stackWithoutEnvWorksWithoutKey() throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("web", YAML, null)))
                .andExpect(status().isCreated());
    }
}
