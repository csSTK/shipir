package dev.bethge.shipir.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class GitCredentialApiTest extends GitTestBase {

    @Test
    void createReturnsHintOnlyAndStoresCiphertext(CapturedOutput output) throws Exception {
        Login l = login("admin");
        String body = mvc.perform(l.write(post("/api/git/credentials")).contentType(APPLICATION_JSON)
                        .content(json("GitHub", "octocat", TOKEN)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("GitHub"))
                .andExpect(jsonPath("$.username").value("octocat"))
                .andExpect(jsonPath("$.tokenHint").isNotEmpty())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(TOKEN).doesNotContain("SECRETTOKEN");

        String list = mvc.perform(get("/api/git/credentials").session(l.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("SECRETTOKEN");

        byte[] stored = jdbc.queryForObject("SELECT token_ciphertext FROM git_credentials", byte[].class);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("SECRETTOKEN");
        assertThat(jdbc.queryForObject("SELECT token_hint FROM git_credentials", String.class))
                .doesNotContain("SECRETTOKEN").hasSizeLessThanOrEqualTo(32);
        assertThat(output.getAll()).doesNotContain("SECRETTOKEN");
    }

    @Test
    void updateKeepsTokenWhenAbsentAndReplacesWhenGiven() throws Exception {
        Login l = login("admin");
        String id = create(l, "GitHub");
        byte[] before = jdbc.queryForObject("SELECT token_ciphertext FROM git_credentials", byte[].class);

        mvc.perform(l.write(put("/api/git/credentials/" + id)).contentType(APPLICATION_JSON)
                        .content(json("GitHub renamed", "monalisa", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("GitHub renamed"))
                .andExpect(jsonPath("$.username").value("monalisa"));
        assertThat(jdbc.queryForObject("SELECT token_ciphertext FROM git_credentials", byte[].class)).isEqualTo(before);

        mvc.perform(l.write(put("/api/git/credentials/" + id)).contentType(APPLICATION_JSON)
                        .content(json("GitHub renamed", "monalisa", "new-token-value-ZZZZ9999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT token_ciphertext FROM git_credentials", byte[].class))
                .isNotEqualTo(before);
    }

    @Test
    void createRequiresTokenAndValidFields() throws Exception {
        Login l = login("admin");
        for (String bad : List.of(json("A", "u", null), json("A", "u", ""), json("", "u", TOKEN),
                json("A", "", TOKEN))) {
            mvc.perform(l.write(post("/api/git/credentials")).contentType(APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM git_credentials", Integer.class)).isZero();
    }

    @Test
    void duplicateNameIs409() throws Exception {
        Login l = login("admin");
        create(l, "GitHub");
        mvc.perform(l.write(post("/api/git/credentials")).contentType(APPLICATION_JSON)
                        .content(json("GitHub", "x", TOKEN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_NAME_TAKEN"));
    }

    @Test
    void unknownIdIs404() throws Exception {
        Login l = login("admin");
        String id = java.util.UUID.randomUUID().toString();
        mvc.perform(l.write(put("/api/git/credentials/" + id)).contentType(APPLICATION_JSON)
                        .content(json("A", "u", null)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));
        mvc.perform(l.write(delete("/api/git/credentials/" + id)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesUnusedCredential() throws Exception {
        Login l = login("admin");
        String id = create(l, "GitHub");
        mvc.perform(l.write(delete("/api/git/credentials/" + id))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM git_credentials", Integer.class)).isZero();
    }

    @Test
    void deleteIsRefusedWhileDeploymentReferencesCredential() throws Exception {
        Login l = login("admin");
        String id = create(l, "GitHub");
        insertDeploymentUsing(id);
        mvc.perform(l.write(delete("/api/git/credentials/" + id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_IN_USE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM git_credentials", Integer.class)).isEqualTo(1);
    }

    @Test
    void onlyAdminHasAccess() throws Exception {
        Login admin = login("admin");
        String id = create(admin, "GitHub");
        for (String role : List.of("operator", "viewer")) {
            Login l = login(role);
            mvc.perform(get("/api/git/credentials").session(l.session())).andExpect(status().isForbidden());
            mvc.perform(l.write(post("/api/git/credentials")).contentType(APPLICATION_JSON)
                    .content(json("X", "u", TOKEN))).andExpect(status().isForbidden());
            mvc.perform(l.write(put("/api/git/credentials/" + id)).contentType(APPLICATION_JSON)
                    .content(json("X", "u", null))).andExpect(status().isForbidden());
            mvc.perform(l.write(delete("/api/git/credentials/" + id))).andExpect(status().isForbidden());
            mvc.perform(l.write(post("/api/git/credentials/" + id + "/test")).contentType(APPLICATION_JSON)
                    .content("{\"repoUrl\":\"https://example.com/r.git\"}")).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/git/credentials")).andExpect(status().isUnauthorized());
    }

    @Test
    void createUpdateDeleteAreAuditedWithoutSecrets() throws Exception {
        Login l = login("admin");
        String id = create(l, "GitHub");
        mvc.perform(l.write(put("/api/git/credentials/" + id)).contentType(APPLICATION_JSON)
                .content(json("GitHub", "u2", "another-secret-token-QQQQ1111"))).andExpect(status().isOk());
        mvc.perform(l.write(delete("/api/git/credentials/" + id))).andExpect(status().isNoContent());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM audit_log WHERE action LIKE 'GIT_CREDENTIAL_%' ORDER BY occurred_at");
        assertThat(rows).extracting(r -> r.get("action"))
                .containsExactly("GIT_CREDENTIAL_CREATE", "GIT_CREDENTIAL_UPDATE", "GIT_CREDENTIAL_DELETE");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("outcome")).isEqualTo("SUCCESS");
            assertThat(r.get("username")).isEqualTo("admin");
            assertThat(r.toString()).doesNotContain("SECRETTOKEN").doesNotContain("QQQQ1111");
        });
    }

    @Test
    void requestToStringHidesToken() {
        assertThat(new dev.bethge.shipir.git.dto.GitCredentialRequest("n", "u", TOKEN).toString())
                .doesNotContain("SECRETTOKEN");
    }

    @Test
    void connectionTestRejectsNonHttpsUrls() throws Exception {
        Login l = login("admin");
        String id = create(l, "GitHub");
        for (String bad : List.of("file:///etc/passwd", "ssh://git@github.com/a/b.git", "git@github.com:a/b.git",
                "/local/path", "http://example.com/r.git", "ftp://example.com/r.git", "https://", "https:///nohost",
                "https://user:pw@example.com/r.git")) {
            MvcResult r = mvc.perform(l.write(post("/api/git/credentials/" + id + "/test"))
                    .contentType(APPLICATION_JSON).content("{\"repoUrl\":\"%s\"}".formatted(bad))).andReturn();
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(r.getResponse().getContentAsString()).as(bad).contains("INVALID_REPO_URL");
        }
    }
}
