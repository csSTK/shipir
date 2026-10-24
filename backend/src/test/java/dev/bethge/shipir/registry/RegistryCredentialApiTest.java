package dev.bethge.shipir.registry;

import dev.bethge.shipir.registry.dto.RegistryCredentialRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

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
class RegistryCredentialApiTest extends RegistryTestBase {

    @Autowired
    RegistryCredentialService service;

    @Test
    void createReturnsHintOnlyAndStoresCiphertext(CapturedOutput output) throws Exception {
        Login l = login("admin");
        String body = mvc.perform(l.write(post("/api/registries")).contentType(APPLICATION_JSON)
                        .content(json("GHCR", "GHCR.io", "robot", SECRET)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("GHCR"))
                .andExpect(jsonPath("$.registryHost").value("ghcr.io"))
                .andExpect(jsonPath("$.username").value("robot"))
                .andExpect(jsonPath("$.secretHint").isNotEmpty())
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SECRETVALUE");

        String list = mvc.perform(get("/api/registries").session(l.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("SECRETVALUE");

        byte[] stored = jdbc.queryForObject("SELECT secret_ciphertext FROM registry_credentials", byte[].class);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("SECRETVALUE");
        assertThat(jdbc.queryForObject("SELECT secret_hint FROM registry_credentials", String.class))
                .doesNotContain("SECRETVALUE").hasSizeLessThanOrEqualTo(32);
        assertThat(output.getAll()).doesNotContain("SECRETVALUE");
    }

    @Test
    void hostIsNormalisedAndDockerHubAliasesShareOneKey() throws Exception {
        Login l = login("admin");
        create(l, "Hub", "Index.Docker.IO");
        assertThat(jdbc.queryForObject("SELECT registry_host FROM registry_credentials", String.class))
                .isEqualTo("docker.io");
        mvc.perform(l.write(post("/api/registries")).contentType(APPLICATION_JSON)
                        .content(json("Hub 2", "docker.io", "u", SECRET)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGISTRY_HOST_TAKEN"));
    }

    @Test
    void invalidHostsAreRejected() throws Exception {
        Login l = login("admin");
        for (String bad : List.of("https://ghcr.io", "ghcr.io/org", "user@ghcr.io", "ghcr io", "ghcr.io:0",
                "ghcr.io:99999", "ghcr.io:", "-bad.example", "a..b", "ghcr.io?x=1", "ghcr.io#f", "..", "reg\\\\x")) {
            mvc.perform(l.write(post("/api/registries")).contentType(APPLICATION_JSON)
                            .content(json("N", bad, "u", SECRET)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REGISTRY_HOST"));
        }
        mvc.perform(l.write(post("/api/registries")).contentType(APPLICATION_JSON)
                        .content(json("N", "registry.example.com:5000", "u", SECRET)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registryHost").value("registry.example.com:5000"));
    }

    @Test
    void updateKeepsSecretWhenAbsentAndReplacesWhenGiven() throws Exception {
        Login l = login("admin");
        String id = create(l, "GHCR", "ghcr.io");
        byte[] before = jdbc.queryForObject("SELECT secret_ciphertext FROM registry_credentials", byte[].class);

        mvc.perform(l.write(put("/api/registries/" + id)).contentType(APPLICATION_JSON)
                        .content(json("GHCR renamed", "ghcr.io", "other", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("GHCR renamed"))
                .andExpect(jsonPath("$.username").value("other"));
        assertThat(jdbc.queryForObject("SELECT secret_ciphertext FROM registry_credentials", byte[].class))
                .isEqualTo(before);

        mvc.perform(l.write(put("/api/registries/" + id)).contentType(APPLICATION_JSON)
                        .content(json("GHCR renamed", "quay.io", "other", "new-secret-value-ZZZZ9999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registryHost").value("quay.io"))
                .andExpect(jsonPath("$.secret").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT secret_ciphertext FROM registry_credentials", byte[].class))
                .isNotEqualTo(before);
    }

    @Test
    void createRequiresSecretAndValidFields() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(post("/api/registries")).contentType(APPLICATION_JSON)
                        .content(json("A", "ghcr.io", "u", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SECRET_REQUIRED"));
        mvc.perform(l.write(post("/api/registries")).contentType(APPLICATION_JSON)
                        .content(json("A", "ghcr.io", "u", "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SECRET_REQUIRED"));
        for (String bad : List.of(json("", "ghcr.io", "u", SECRET), json("A", "", "u", SECRET),
                json("A", "ghcr.io", "", SECRET))) {
            mvc.perform(l.write(post("/api/registries")).contentType(APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM registry_credentials", Integer.class)).isZero();
    }

    @Test
    void duplicateNameAndHostAre409() throws Exception {
        Login l = login("admin");
        String id = create(l, "GHCR", "ghcr.io");
        mvc.perform(l.write(post("/api/registries")).contentType(APPLICATION_JSON)
                        .content(json("GHCR", "quay.io", "x", SECRET)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REGISTRY_NAME_TAKEN"));
        mvc.perform(l.write(post("/api/registries")).contentType(APPLICATION_JSON)
                        .content(json("Other", "ghcr.io", "x", SECRET)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REGISTRY_HOST_TAKEN"));
        create(l, "Quay", "quay.io");
        mvc.perform(l.write(put("/api/registries/" + id)).contentType(APPLICATION_JSON)
                        .content(json("GHCR", "quay.io", "x", null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REGISTRY_HOST_TAKEN"));
    }

    @Test
    void unknownIdIs404() throws Exception {
        Login l = login("admin");
        String id = java.util.UUID.randomUUID().toString();
        mvc.perform(l.write(put("/api/registries/" + id)).contentType(APPLICATION_JSON)
                        .content(json("A", "ghcr.io", "u", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGISTRY_CREDENTIAL_NOT_FOUND"));
        mvc.perform(l.write(delete("/api/registries/" + id))).andExpect(status().isNotFound());
        mvc.perform(l.write(post("/api/registries/" + id + "/test"))).andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesCredential() throws Exception {
        Login l = login("admin");
        String id = create(l, "GHCR", "ghcr.io");
        mvc.perform(l.write(delete("/api/registries/" + id))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM registry_credentials", Integer.class)).isZero();
    }

    @Test
    void onlyAdminHasAccess() throws Exception {
        Login admin = login("admin");
        String id = create(admin, "GHCR", "ghcr.io");
        for (String role : List.of("operator", "viewer")) {
            Login l = login(role);
            mvc.perform(get("/api/registries").session(l.session())).andExpect(status().isForbidden());
            mvc.perform(l.write(post("/api/registries")).contentType(APPLICATION_JSON)
                    .content(json("X", "quay.io", "u", SECRET))).andExpect(status().isForbidden());
            mvc.perform(l.write(put("/api/registries/" + id)).contentType(APPLICATION_JSON)
                    .content(json("X", "ghcr.io", "u", null))).andExpect(status().isForbidden());
            mvc.perform(l.write(delete("/api/registries/" + id))).andExpect(status().isForbidden());
            mvc.perform(l.write(post("/api/registries/" + id + "/test"))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/registries")).andExpect(status().isUnauthorized());
        // CSRF: a write without the token is refused even for ADMIN
        mvc.perform(post("/api/registries").session(admin.session()).contentType(APPLICATION_JSON)
                .content(json("X", "quay.io", "u", SECRET))).andExpect(status().isForbidden());
    }

    @Test
    void createUpdateDeleteAreAuditedWithoutSecrets() throws Exception {
        Login l = login("admin");
        String id = create(l, "GHCR", "ghcr.io");
        mvc.perform(l.write(put("/api/registries/" + id)).contentType(APPLICATION_JSON)
                .content(json("GHCR", "ghcr.io", "u2", "another-secret-QQQQ1111"))).andExpect(status().isOk());
        mvc.perform(l.write(delete("/api/registries/" + id))).andExpect(status().isNoContent());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM audit_log WHERE action LIKE 'REGISTRY_CREDENTIAL_%' ORDER BY occurred_at");
        assertThat(rows).extracting(r -> r.get("action"))
                .containsExactly("REGISTRY_CREDENTIAL_CREATE", "REGISTRY_CREDENTIAL_UPDATE",
                        "REGISTRY_CREDENTIAL_DELETE");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("outcome")).isEqualTo("SUCCESS");
            assertThat(r.get("username")).isEqualTo("admin");
            assertThat(r.toString()).doesNotContain("SECRETVALUE").doesNotContain("QQQQ1111");
        });
    }

    @Test
    void decryptedCredentialsReturnsPlaintextForInternalCallers() throws Exception {
        Login l = login("admin");
        create(l, "GHCR", "ghcr.io");
        List<RegistryAuth> auths = service.decryptedCredentials();
        assertThat(auths).containsExactly(new RegistryAuth("ghcr.io", "robot", SECRET));
        assertThat(auths.get(0).toString()).doesNotContain("SECRETVALUE");
    }

    @Test
    void requestToStringHidesSecret() {
        assertThat(new RegistryCredentialRequest("n", "ghcr.io", "u", SECRET).toString())
                .doesNotContain("SECRETVALUE");
    }
}
