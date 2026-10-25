package dev.bethge.shipir.compose;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code PUT .../image-auto-update}: validation, FILE-only, roles, permissions, CSRF, audit, detail exposure. */
class ImageAutoUpdateApiTest extends DeploymentTestBase {

    private UUID gitDeployment;
    private UUID fileDeployment;

    @BeforeEach
    void deployments() {
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by, source_type, git_repo_url, "
                + "git_ref, git_compose_path) SELECT 'g', ?, 'x', u.id, 'GIT', 'https://example.com/r.git', 'main', "
                + "'compose.yaml' FROM users u WHERE u.username = 'admin'", hostId);
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'f', ?, 'x', u.id FROM users u WHERE u.username = 'admin'", hostId);
        jdbc.update("INSERT INTO deployment_versions (deployment_id, yaml_content, created_by) "
                + "SELECT s.id, ?, s.created_by FROM deployments s", YAML);
        jdbc.update("UPDATE deployments SET current_version_id = (SELECT id FROM deployment_versions v WHERE v.deployment_id = deployments.id)");
        gitDeployment = jdbc.queryForObject("SELECT id FROM deployments WHERE name = 'g'", UUID.class);
        fileDeployment = jdbc.queryForObject("SELECT id FROM deployments WHERE name = 'f'", UUID.class);
    }

    private String path(UUID id) {
        return url() + "/" + id + "/image-auto-update";
    }

    @Test
    void enablesWithWindowAndExposesItInDetail() throws Exception {
        Login op = login("operator");
        mvc.perform(op.write(put(path(fileDeployment))).contentType(APPLICATION_JSON)
                        .content("{\"enabled\":true,\"windowStart\":\"02:00\",\"windowEnd\":\"05:30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageAutoUpdate.enabled").value(true))
                .andExpect(jsonPath("$.imageAutoUpdate.windowStart").value("02:00"))
                .andExpect(jsonPath("$.imageAutoUpdate.windowEnd").value("05:30"));
        mvc.perform(get(url() + "/" + fileDeployment).session(op.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageAutoUpdate.enabled").value(true))
                .andExpect(jsonPath("$.imageAutoUpdate.windowStart").value("02:00"))
                .andExpect(jsonPath("$.imageAutoUpdate.lastAttemptAt").doesNotExist())
                .andExpect(jsonPath("$.git").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT image_auto_update FROM deployments WHERE id = ?", Boolean.class, fileDeployment))
                .isTrue();
        assertThat(jdbc.queryForList("SELECT outcome FROM audit_log WHERE action = 'DEPLOYMENT_IMAGE_AUTO_UPDATE_CONFIG'",
                String.class)).containsExactly("SUCCESS");
    }

    @Test
    void enablingWithoutWindowMeansAnytimeAndDisablingClearsIt() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put(path(fileDeployment))).contentType(APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageAutoUpdate.windowStart").doesNotExist());
        mvc.perform(admin.write(put(path(fileDeployment))).contentType(APPLICATION_JSON)
                        .content("{\"enabled\":false,\"windowStart\":\"01:00\",\"windowEnd\":\"02:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageAutoUpdate.enabled").value(false))
                .andExpect(jsonPath("$.imageAutoUpdate.windowStart").value("01:00"));
    }

    @Test
    void defaultsAreOffAndWindowAbsent() throws Exception {
        Login admin = login("admin");
        mvc.perform(get(url() + "/" + fileDeployment).session(admin.session()))
                .andExpect(jsonPath("$.imageAutoUpdate.enabled").value(false))
                .andExpect(jsonPath("$.imageAutoUpdate.windowStart").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"enabled\":true,\"windowStart\":\"02:00\"}",
            "{\"enabled\":true,\"windowEnd\":\"05:00\"}",
            "{\"enabled\":true,\"windowStart\":\"03:00\",\"windowEnd\":\"03:00\"}",
            "{\"enabled\":true,\"windowStart\":\"25:00\",\"windowEnd\":\"05:00\"}",
            "{\"enabled\":true,\"windowStart\":\"2am\",\"windowEnd\":\"5am\"}",
            "{\"windowStart\":\"02:00\",\"windowEnd\":\"05:00\"}"})
    void invalidWindowOrMissingFlagIs400AndChangesNothing(String body) throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put(path(fileDeployment))).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT image_auto_update FROM deployments WHERE id = ?", Boolean.class, fileDeployment))
                .isFalse();
    }

    @Test
    void gitDeploymentsAreRejected() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put(path(gitDeployment))).contentType(APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_A_FILE_DEPLOYMENT"));
    }

    @Test
    void unknownDeploymentIs404() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put(path(UUID.randomUUID()))).contentType(APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewerGets403() throws Exception {
        Login viewer = login("viewer");
        mvc.perform(viewer.write(put(path(fileDeployment))).contentType(APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorWithoutAnyGrantGets404() throws Exception {
        jdbc.update("DELETE FROM permissions");
        Login op = login("operator");
        mvc.perform(op.write(put(path(fileDeployment))).contentType(APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void operatorWithOnlyViewOnTheDeploymentGets403() throws Exception {
        jdbc.update("DELETE FROM permissions");
        grantDeployment("operator", fileDeployment, "VIEW");
        Login op = login("operator");
        mvc.perform(op.write(put(path(fileDeployment))).contentType(APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void requestWithoutCsrfTokenIsRejected() throws Exception {
        Login admin = login("admin");
        mvc.perform(put(path(fileDeployment)).session(admin.session()).contentType(APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT image_auto_update FROM deployments WHERE id = ?", Boolean.class, fileDeployment))
                .isFalse();
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(put(path(fileDeployment)).contentType(APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().is4xxClientError());
    }
}
