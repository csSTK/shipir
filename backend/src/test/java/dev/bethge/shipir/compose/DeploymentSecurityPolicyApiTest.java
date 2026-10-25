package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The fake compose emits the YAML as JSON, so volumes use the long form that real {@code config --format json} normalizes to.
 * Non-ADMIN users cannot reach host root through Compose; ADMIN can; system runs follow the content author's role. */
class DeploymentSecurityPolicyApiTest extends DeploymentTestBase {

    private static final String PRIVILEGED = "services:\n  x:\n    image: alpine\n    privileged: true\n";
    private static final String ROOT_MOUNT = "services:\n  x:\n    image: alpine\n    volumes:\n      - {type: bind, source: /, target: /host}\n";

    @Autowired
    DeploymentRunService runs;

    private static String body(String name, String yaml) {
        return JsonMapper.builder().build().writeValueAsString(
                name == null ? Map.of("yaml", yaml) : Map.of("name", name, "yaml", yaml));
    }

    private UUID create(Login who, String name, String yaml) throws Exception {
        String res = mvc.perform(who.write(post(url()).contentType(APPLICATION_JSON).content(body(name, yaml))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(res, "$.id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "privileged: true", "cap_add: [SYS_ADMIN]", "pid: host", "ipc: host", "network_mode: host",
            "devices: ['/dev/sda:/dev/sda']", "security_opt: ['seccomp:unconfined']", "userns_mode: host",
            "volumes: [{type: bind, source: /, target: /host}]",
            "volumes: [{type: bind, source: /var/run/docker.sock, target: /var/run/docker.sock}]"})
    void operatorCannotCreateDeploymentWithHostLevelSetting(String setting) throws Exception {
        Login op = login("operator");
        String yaml = "services:\n  x:\n    image: alpine\n    " + setting + "\n";
        mvc.perform(op.write(post(url()).contentType(APPLICATION_JSON).content(body("evil", yaml))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
    }

    @Test
    void adminMayCreateAndDeployPrivilegedDeployment() throws Exception {
        Login admin = login("admin");
        UUID id = create(admin, "ok", PRIVILEGED);
        mvc.perform(admin.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
    }

    @Test
    void operatorMayUseNamedVolumesAndPlainServices() throws Exception {
        create(login("operator"), "fine", "services:\n  x:\n    image: nginx\n    volumes:\n      - data:/data\nvolumes:\n  data: {}\n");
    }

    @Test
    void operatorCannotUpdateToHostLevelSettingAndStateStaysUntouched() throws Exception {
        Login op = login("operator");
        UUID id = create(op, "upd", YAML);
        mvc.perform(op.write(put(url() + "/" + id).contentType(APPLICATION_JSON).content(body(null, ROOT_MOUNT))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        assertThat(files.readCompose(hostId, "upd")).contains(YAML);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment_versions", Integer.class)).isEqualTo(1);
    }

    @Test
    void operatorCannotDeployOrRollbackVersionAuthoredByAdmin() throws Exception {
        UUID id = create(login("admin"), "adminmade", PRIVILEGED);
        UUID versionId = jdbc.queryForObject("SELECT id FROM deployment_versions", UUID.class);
        Login op = login("operator");
        mvc.perform(op.write(post(url() + "/" + id + "/deploy")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        mvc.perform(op.write(post(url() + "/" + id + "/versions/" + versionId + "/rollback").contentType(APPLICATION_JSON)))
                .andExpect(status().isForbidden());
        assertThat(commands).doesNotContain(ComposeCommand.UP);
    }

    @Test
    void systemRunOfContentAuthoredByAdminIsAllowed() throws Exception {
        UUID id = create(login("admin"), "sysok", PRIVILEGED);
        jdbc.update("UPDATE deployment_versions SET deployed_at = now() WHERE deployment_id = ?", id);
        assertThat(runs.webhookTrigger(id).runId()).isNotBlank();
    }

    @Test
    void systemRunOfContentAuthoredByOperatorIsDenied() throws Exception {
        UUID id = create(login("admin"), "sysdeny", PRIVILEGED);
        jdbc.update("UPDATE deployment_versions SET deployed_at = now(), created_by = (SELECT id FROM users WHERE username = 'operator') "
                + "WHERE deployment_id = ?", id);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runs.webhookTrigger(id))
                .isInstanceOfSatisfying(DeploymentException.class, e -> assertThat(e.code()).isEqualTo("ADMIN_REQUIRED"));
        assertThat(commands).doesNotContain(ComposeCommand.PULL, ComposeCommand.UP);
    }
}
