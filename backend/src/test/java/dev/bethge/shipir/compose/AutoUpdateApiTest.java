package dev.bethge.shipir.compose;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code PUT .../git/auto-update}: validation, FILE deployments, roles, audit. */
class AutoUpdateApiTest extends DeploymentTestBase {

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
        return url() + "/" + id + "/git/auto-update";
    }

    @Test
    void enablesWithIntervalAndExposesItInDetail() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put(path(gitDeployment))).contentType(APPLICATION_JSON)
                        .content("{\"enabled\":true,\"intervalSeconds\":120}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.git.autoUpdateEnabled").value(true))
                .andExpect(jsonPath("$.git.autoUpdateIntervalSeconds").value(120));
        assertThat(jdbc.queryForObject("SELECT auto_update_enabled FROM deployments WHERE id = ?", Boolean.class, gitDeployment))
                .isTrue();
        assertThat(jdbc.queryForList("SELECT outcome FROM audit_log WHERE action = 'DEPLOYMENT_AUTO_UPDATE_CONFIG'",
                String.class)).containsExactly("SUCCESS");
    }

    @Test
    void operatorMayConfigureAndKeepsIntervalWhenDisabling() throws Exception {
        Login op = login("operator");
        mvc.perform(op.write(put(path(gitDeployment))).contentType(APPLICATION_JSON)
                .content("{\"enabled\":true,\"intervalSeconds\":90}")).andExpect(status().isOk());
        mvc.perform(op.write(put(path(gitDeployment))).contentType(APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.git.autoUpdateEnabled").value(false))
                .andExpect(jsonPath("$.git.autoUpdateIntervalSeconds").value(90));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"enabled\":true,\"intervalSeconds\":59}", "{\"enabled\":true,\"intervalSeconds\":0}",
            "{\"enabled\":true}", "{\"intervalSeconds\":120}", "{\"enabled\":\"yes\"}"})
    void rejectsInvalidSettings(String body) throws Exception {
        mvc.perform(login("admin").write(put(path(gitDeployment))).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT auto_update_enabled FROM deployments WHERE id = ?", Boolean.class, gitDeployment))
                .isFalse();
    }

    @Test
    void fileDeploymentIsRejected() throws Exception {
        mvc.perform(login("admin").write(put(path(fileDeployment))).contentType(APPLICATION_JSON)
                        .content("{\"enabled\":true,\"intervalSeconds\":120}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_A_GIT_DEPLOYMENT"));
    }

    @Test
    void unknownDeploymentIs404() throws Exception {
        mvc.perform(login("admin").write(put(path(UUID.randomUUID()))).contentType(APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isNotFound());
    }

    @Test
    void viewerIsForbiddenAndAnonymousUnauthorized() throws Exception {
        mvc.perform(login("viewer").write(put(path(gitDeployment))).contentType(APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isForbidden());
        mvc.perform(put(path(gitDeployment)).contentType(APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().is4xxClientError());
    }
}
