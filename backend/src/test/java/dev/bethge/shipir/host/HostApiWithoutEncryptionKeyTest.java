package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** No {@code shipir.encryption-key}: LOCAL hosts work, TCP hosts cannot be stored. */
@TestPropertySource(properties = "shipir.encryption-key=")
class HostApiWithoutEncryptionKeyTest extends HostApiTestBase {

    @Test
    void tcpHostWithoutEncryptionKeyIsConflict() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content(tcpBody("Remote", "127.0.0.1", 1, pems())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENCRYPTION_KEY_MISSING"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM docker_hosts", Integer.class)).isZero();
    }

    @Test
    void localHostWorksWithoutEncryptionKey() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Local\",\"connectionType\":\"LOCAL\"}"))
                .andExpect(status().isCreated());
    }
}
