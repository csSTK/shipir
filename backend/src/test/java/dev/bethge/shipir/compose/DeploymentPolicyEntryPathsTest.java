package dev.bethge.shipir.compose;

import dev.bethge.shipir.ai.tools.AiToolRegistry;
import dev.bethge.shipir.ai.tools.ProposalResult;
import dev.bethge.shipir.ai.tools.ToolContext;
import dev.bethge.shipir.ai.tools.ToolResult;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.git.GitCheckout;
import dev.bethge.shipir.git.GitRepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ComposeSecurityPolicy must hold on every entry path that stores or runs a compose file, not just the plain
 * create/update endpoints: adoption, Git create and the AI tools. A non-ADMIN gets 403 ADMIN_REQUIRED and nothing is
 * left behind; ADMIN is exempt. (Git redeploy, pull-update, image auto-update, webhook and env-only update are in
 * {@link DeploymentSecurityPolicyApiTest} and {@link GitDeploymentApiTest}.) Compose is the YAML-passthrough fake.
 */
class DeploymentPolicyEntryPathsTest extends DeploymentTestBase {

    private static final String PRIVILEGED = "services:\n  x:\n    image: alpine\n    privileged: true\n";

    @MockitoBean
    ContainerService containers;
    @MockitoBean
    GitRepositoryService git;
    @Autowired
    AiToolRegistry registry;

    @BeforeEach
    void stubs() {
        when(containers.listContainers(any(), any())).thenReturn(List.of(new ContainerDto("c1", "x-1", "alpine", "running",
                "Up", null, Instant.now(), List.of(), "legacy", "x")));
        when(git.sync(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Path file = ((Path) inv.getArgument(0)).resolve((String) inv.getArgument(3));
            Files.createDirectories(file.getParent());
            Files.writeString(file, PRIVILEGED);
            return new GitCheckout("abc123", PRIVILEGED);
        });
    }

    private int deployments() {
        return jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class);
    }

    private static String json(Object value) {
        return JsonMapper.builder().build().writeValueAsString(value);
    }

    // ---- adoption ----

    private String adoptUrl() {
        return "/api/hosts/" + hostId + "/deployments/legacy/adopt";
    }

    @Test
    void operatorCannotAdoptAProjectWithAPrivilegedService() throws Exception {
        mvc.perform(login("operator").write(post(adoptUrl()).contentType(APPLICATION_JSON).content(json(Map.of("yaml", PRIVILEGED)))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        assertThat(deployments()).isZero();
        assertThat(commands).doesNotContain(ComposeCommand.UP);
    }

    @Test
    void adminMayAdoptAProjectWithAPrivilegedService() throws Exception {
        mvc.perform(login("admin").write(post(adoptUrl()).contentType(APPLICATION_JSON).content(json(Map.of("yaml", PRIVILEGED)))))
                .andExpect(status().isCreated());
        assertThat(deployments()).isEqualTo(1);
    }

    // ---- Git create ----

    private String gitBody(String name) {
        return json(Map.of("name", name, "source", "GIT", "repoUrl", "https://example.com/r.git", "ref", "main",
                "composePath", "compose.yaml"));
    }

    @Test
    void operatorCannotCreateAGitDeploymentWhoseRepositoryHoldsAPrivilegedService() throws Exception {
        mvc.perform(login("operator").write(post(url())).contentType(APPLICATION_JSON).content(gitBody("gitevil")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        assertThat(deployments()).isZero();
        assertThat(Files.exists(files.repoDir(hostId, "gitevil"))).isFalse();
    }

    @Test
    void adminMayCreateAGitDeploymentWhoseRepositoryHoldsAPrivilegedService() throws Exception {
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON).content(gitBody("gitadmin")))
                .andExpect(status().isCreated());
    }

    // ---- AI tools (run as the signed-in user's role) ----

    private ToolContext ctx(Role role) {
        String name = role.name().toLowerCase();
        return new ToolContext(users.findByUsername(name).orElseThrow().getId(), name, role);
    }

    private ToolResult confirmed(String tool, Map<String, Object> args, Role role) {
        ProposalResult p = registry.propose(tool, json(args), ctx(role), UUID.randomUUID());
        assertThat(p.accepted()).as(tool + ": " + p.error()).isTrue();
        return registry.executeConfirmed(p.action().token(), ctx(role));
    }

    @Test
    void createDeploymentToolRefusesAPrivilegedServiceForOperator() {
        ToolResult result = confirmed("create_deployment",
                Map.of("hostId", hostId.toString(), "name", "aievil", "yaml", PRIVILEGED), Role.OPERATOR);
        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("ADMIN_REQUIRED");
        assertThat(deployments()).isZero();
    }

    @Test
    void createDeploymentToolAllowsAPrivilegedServiceForAdmin() {
        ToolResult result = confirmed("create_deployment",
                Map.of("hostId", hostId.toString(), "name", "aiadmin", "yaml", PRIVILEGED), Role.ADMIN);
        assertThat(result.error()).as(result.content()).isFalse();
    }

    @Test
    void updateDeploymentToolRefusesAPrivilegedServiceForOperator() throws Exception {
        String res = mvc.perform(login("operator").write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("aiupd", YAML, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(res, "$.id");
        ToolResult result = confirmed("update_deployment",
                Map.of("hostId", hostId.toString(), "deploymentId", id, "yaml", PRIVILEGED), Role.OPERATOR);
        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("ADMIN_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment_versions", Integer.class)).isEqualTo(1);
    }

    @Test
    void runDeploymentToolRefusesAnAdminAuthoredPrivilegedDeploymentForOperator() throws Exception {
        String res = mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("airun", PRIVILEGED, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(res, "$.id");
        commands.clear();
        ToolResult result = confirmed("run_deployment", Map.of("hostId", hostId.toString(), "deploymentId", id), Role.OPERATOR);
        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("ADMIN_REQUIRED");
        assertThat(commands).doesNotContain(ComposeCommand.UP);
    }
}
