package dev.bethge.shipir.git;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "shipir.encryption-key=")
class GitCredentialWithoutKeyTest extends GitTestBase {

    @Test
    void createWithoutEncryptionKeyIsRejected() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(post("/api/git/credentials")).contentType(APPLICATION_JSON)
                        .content(json("A", "u", TOKEN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENCRYPTION_KEY_MISSING"));
        mvc.perform(get("/api/git/credentials").session(l.session())).andExpect(status().isOk());
    }
}
