package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bind mounts on a remote (TCP) host (shipr-kqbc.61): non-ADMIN users, and automatic runs under the same rules, get
 * 403 REMOTE_BIND_MOUNT_DENIED for any bind mount; named volumes and ADMIN deploys stay allowed.
 */
class DeploymentRemoteBindPolicyApiTest extends DeploymentTestBase {

    private static final String BIND = "services:\n  x:\n    image: alpine\n    volumes:\n      - {type: bind, source: ./data, target: /d}\n";
    private static final String NAMED = "services:\n  x:\n    image: alpine\n    volumes:\n      - data:/d\nvolumes:\n  data: {}\n";

    @Autowired
    DeploymentRunService runs;
    @Autowired
    dev.bethge.shipir.compose.generator.ComposeGeneratorService generator;
    @Autowired
    dev.bethge.shipir.ai.tools.AiToolRegistry registry;

    /** The real resolver checks egress and decrypts TLS material; only the endpoint type matters here. */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    dev.bethge.shipir.host.HostEndpointResolver resolver;

    /** The ownership check of named volumes asks the host; there is no remote daemon here. */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    dev.bethge.shipir.docker.client.VolumeService volumeService;

    @BeforeEach
    void makeHostRemote() {
        org.mockito.Mockito.when(resolver.endpointOf(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new dev.bethge.shipir.docker.DockerEndpoint.Tcp("remote.example", 2375, null));
        jdbc.update("UPDATE docker_hosts SET connection_type = 'TCP', host = 'remote.example', port = 2375 WHERE id = ?", hostId);
    }

    private static String body(String name, String yaml) {
        return JsonMapper.builder().build().writeValueAsString(Map.of("name", name, "yaml", yaml));
    }

    private UUID create(Login who, String name, String yaml) throws Exception {
        String res = mvc.perform(who.write(post(url()).contentType(APPLICATION_JSON).content(body(name, yaml))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(res, "$.id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"./data", "/srv/data", "/var/run/docker.sock"})
    void operatorCannotCreateDeploymentWithBindMountOnRemoteHost(String source) throws Exception {
        String yaml = "services:\n  x:\n    image: alpine\n    volumes:\n      - {type: bind, source: " + source + ", target: /d}\n";
        mvc.perform(login("operator").write(post(url()).contentType(APPLICATION_JSON).content(body("evil", yaml))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REMOTE_BIND_MOUNT_DENIED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
    }

    @Test
    void operatorMayUseNamedVolumesOnRemoteHost() throws Exception {
        create(login("operator"), "named", NAMED);
    }

    @Test
    void adminMayUseBindMountsOnRemoteHost() throws Exception {
        create(login("admin"), "adminbind", BIND);
    }

    @Test
    void operatorCannotDeployAdminAuthoredBindOnRemoteHost() throws Exception {
        UUID id = create(login("admin"), "adminmade", BIND);
        mvc.perform(login("operator").write(post(url() + "/" + id + "/deploy")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REMOTE_BIND_MOUNT_DENIED"));
        assertThat(commands).doesNotContain(ComposeCommand.UP);
    }

    @Test
    void systemRunOfOperatorAuthoredBindOnRemoteHostIsDenied() throws Exception {
        UUID id = create(login("admin"), "sysdeny", BIND);
        jdbc.update("UPDATE deployment_versions SET deployed_at = now(), deploy_succeeded_at = now(), created_by = "
                + "(SELECT id FROM users WHERE username = 'operator') WHERE deployment_id = ?", id);
        assertThatThrownBy(() -> runs.webhookTrigger(id)).isInstanceOfSatisfying(DeploymentException.class,
                e -> assertThat(e.code()).isEqualTo("REMOTE_BIND_MOUNT_DENIED"));
        assertThat(commands).doesNotContain(ComposeCommand.PULL, ComposeCommand.UP);
    }

    @Test
    void systemRunOfAdminAuthoredBindOnRemoteHostIsAllowed() throws Exception {
        UUID id = create(login("admin"), "sysok", BIND);
        jdbc.update("UPDATE deployment_versions SET deployed_at = now(), deploy_succeeded_at = now() WHERE deployment_id = ?", id);
        assertThat(runs.webhookTrigger(id).runId()).isNotBlank();
    }

    // ---- wizard: generator output (short syntax ./x:/d) ----

    /** What {@code docker compose config --format json} does to the short form; the fake compose only echoes the YAML. */
    private String normalizedLikeCompose(String yaml) {
        var mapper = tools.jackson.dataformat.yaml.YAMLMapper.builder().build();
        var root = (tools.jackson.databind.node.ObjectNode) mapper.readTree(yaml);
        root.path("services").forEach(service -> {
            var volumes = (tools.jackson.databind.node.ArrayNode) service.path("volumes");
            for (int i = 0; i < volumes.size(); i++) {
                String[] parts = volumes.get(i).asString().split(":");
                if (parts[0].startsWith(".") || parts[0].startsWith("/")) {
                    volumes.set(i, mapper.createObjectNode().put("type", "bind").put("source", parts[0]).put("target", parts[1]));
                }
            }
        });
        return mapper.writeValueAsString(root);
    }

    private String wizardYaml() {
        String generated = generator.generate(new dev.bethge.shipir.compose.generator.model.ComposeSpec(
                java.util.List.of(new dev.bethge.shipir.compose.generator.model.ServiceSpec("web", "nginx:1.27", null, null, null, null,
                        java.util.List.of(new dev.bethge.shipir.compose.generator.model.VolumeMountSpec("./data", "/d", false)),
                        null, null, null)), null, null));
        assertThat(generated).contains("./data:/d");
        return normalizedLikeCompose(generated);
    }

    @Test
    void wizardGeneratedRelativeBindIsRefusedForOperatorOnRemoteHost() throws Exception {
        mvc.perform(login("operator").write(post(url()).contentType(APPLICATION_JSON).content(body("wiz", wizardYaml()))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REMOTE_BIND_MOUNT_DENIED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
    }

    @Test
    void wizardGeneratedRelativeBindIsAllowedForAdminOnRemoteHost() throws Exception {
        create(login("admin"), "wizadmin", wizardYaml());
    }

    // ---- AI write tools ----

    private dev.bethge.shipir.ai.tools.ToolContext ctx(dev.bethge.shipir.auth.Role role) {
        String name = role.name().toLowerCase();
        return new dev.bethge.shipir.ai.tools.ToolContext(users.findByUsername(name).orElseThrow().getId(), name, role);
    }

    private dev.bethge.shipir.ai.tools.ToolResult confirmed(String tool, Map<String, Object> args, dev.bethge.shipir.auth.Role role) {
        var p = registry.propose(tool, JsonMapper.builder().build().writeValueAsString(args), ctx(role), UUID.randomUUID());
        assertThat(p.accepted()).as(tool + ": " + p.error()).isTrue();
        return registry.executeConfirmed(p.action().token(), ctx(role));
    }

    @Test
    void createDeploymentToolRefusesABindMountForOperatorOnRemoteHost() {
        var result = confirmed("create_deployment", Map.of("hostId", hostId.toString(), "name", "aibind",
                "yaml", BIND), dev.bethge.shipir.auth.Role.OPERATOR);
        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("REMOTE_BIND_MOUNT_DENIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
    }

    @Test
    void updateDeploymentToolRefusesABindMountForOperatorOnRemoteHost() throws Exception {
        UUID id = create(login("operator"), "aiupd", NAMED);
        var result = confirmed("update_deployment", Map.of("hostId", hostId.toString(), "deploymentId", id.toString(),
                "yaml", BIND), dev.bethge.shipir.auth.Role.OPERATOR);
        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("REMOTE_BIND_MOUNT_DENIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment_versions", Integer.class)).isEqualTo(1);
    }
}
