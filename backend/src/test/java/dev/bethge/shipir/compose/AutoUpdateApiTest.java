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

/** {@code PUT .../git/auto-update}: validation, FILE stacks, roles, audit. */
class AutoUpdateApiTest extends StackTestBase {

    private UUID gitStack;
    private UUID fileStack;

    @BeforeEach
    void stacks() {
        jdbc.update("INSERT INTO stacks (name, host_id, compose_file_path, created_by, source_type, git_repo_url, "
                + "git_ref, git_compose_path) SELECT 'g', ?, 'x', u.id, 'GIT', 'https://example.com/r.git', 'main', "
                + "'compose.yaml' FROM users u WHERE u.username = 'admin'", hostId);
        jdbc.update("INSERT INTO stacks (name, host_id, compose_file_path, created_by) "
                + "SELECT 'f', ?, 'x', u.id FROM users u WHERE u.username = 'admin'", hostId);
        jdbc.update("INSERT INTO stack_versions (stack_id, yaml_content, created_by) "
                + "SELECT s.id, ?, s.created_by FROM stacks s", YAML);
        jdbc.update("UPDATE stacks SET current_version_id = (SELECT id FROM stack_versions v WHERE v.stack_id = stacks.id)");
        gitStack = jdbc.queryForObject("SELECT id FROM stacks WHERE name = 'g'", UUID.class);
        fileStack = jdbc.queryForObject("SELECT id FROM stacks WHERE name = 'f'", UUID.class);
    }

    private String path(UUID id) {
        return url() + "/" + id + "/git/auto-update";
    }

    @Test
    void enablesWithIntervalAndExposesItInDetail() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put(path(gitStack))).contentType(APPLICATION_JSON)
                        .content("{\"enabled\":true,\"intervalSeconds\":120}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.git.autoUpdateEnabled").value(true))
                .andExpect(jsonPath("$.git.autoUpdateIntervalSeconds").value(120));
        assertThat(jdbc.queryForObject("SELECT auto_update_enabled FROM stacks WHERE id = ?", Boolean.class, gitStack))
                .isTrue();
        assertThat(jdbc.queryForList("SELECT outcome FROM audit_log WHERE action = 'STACK_AUTO_UPDATE_CONFIG'",
                String.class)).containsExactly("SUCCESS");
    }

    @Test
    void operatorMayConfigureAndKeepsIntervalWhenDisabling() throws Exception {
        Login op = login("operator");
        mvc.perform(op.write(put(path(gitStack))).contentType(APPLICATION_JSON)
                .content("{\"enabled\":true,\"intervalSeconds\":90}")).andExpect(status().isOk());
        mvc.perform(op.write(put(path(gitStack))).contentType(APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.git.autoUpdateEnabled").value(false))
                .andExpect(jsonPath("$.git.autoUpdateIntervalSeconds").value(90));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"enabled\":true,\"intervalSeconds\":59}", "{\"enabled\":true,\"intervalSeconds\":0}",
            "{\"enabled\":true}", "{\"intervalSeconds\":120}", "{\"enabled\":\"yes\"}"})
    void rejectsInvalidSettings(String body) throws Exception {
        mvc.perform(login("admin").write(put(path(gitStack))).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT auto_update_enabled FROM stacks WHERE id = ?", Boolean.class, gitStack))
                .isFalse();
    }

    @Test
    void fileStackIsRejected() throws Exception {
        mvc.perform(login("admin").write(put(path(fileStack))).contentType(APPLICATION_JSON)
                        .content("{\"enabled\":true,\"intervalSeconds\":120}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_A_GIT_STACK"));
    }

    @Test
    void unknownStackIs404() throws Exception {
        mvc.perform(login("admin").write(put(path(UUID.randomUUID()))).contentType(APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isNotFound());
    }

    @Test
    void viewerIsForbiddenAndAnonymousUnauthorized() throws Exception {
        mvc.perform(login("viewer").write(put(path(gitStack))).contentType(APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isForbidden());
        mvc.perform(put(path(gitStack)).contentType(APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().is4xxClientError());
    }
}
