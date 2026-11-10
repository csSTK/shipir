package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Container lifecycle actions: host OPERATE, or OPERATE on the managed deployment owning the container (like exec). */
class HostContainerLifecycleAccessTest extends HostApiTestBase {

    @MockitoBean ContainerService containers;
    @MockitoBean ImageService images;
    @MockitoBean NetworkService networks;
    @MockitoBean VolumeService volumes;

    private UUID host;
    private UUID shop;

    @Override
    protected boolean autoGrantHosts() {
        return false;
    }

    @BeforeEach
    void setUp() {
        SetupGateFixture.resetWithAdminHost(jdbc, "local");
        host = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        shop = deployment("shop");
        deployment("blog");
        when(containers.inspect(any(), any(), eq("shop1"))).thenReturn(details("shop"));
        when(containers.inspect(any(), any(), eq("blog1"))).thenReturn(details("blog"));
        when(containers.inspect(any(), any(), eq("plain1"))).thenReturn(details(null));
        when(containers.inspect(any(), any(), eq("missing1")))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "no such container", null));
    }

    private UUID deployment(String name) {
        return jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT ?, ?, 'x', id FROM users WHERE username = 'admin' RETURNING id", UUID.class, name, host);
    }

    private void grantDeployment(String user, UUID dep, String level) {
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, ? "
                + "FROM users WHERE username = ?", dep, level, user);
    }

    private static ContainerDetailsDto details(String project) {
        return new ContainerDetailsDto("id1", "web", "nginx:1", "running", null, null, 0, null, List.of(), List.of(),
                false, false, List.of(), List.of(), Map.of(), List.of(), List.of(), null, project,
                project == null ? null : "web");
    }

    @Test
    void deploymentOperateGrantMayActOnOwnContainersOnly() throws Exception {
        grantDeployment("operator", shop, "OPERATE");
        var op = login("operator");

        mvc.perform(op.write(post("/api/hosts/{h}/containers/shop1/start", host))).andExpect(status().isNoContent());
        mvc.perform(op.write(post("/api/hosts/{h}/containers/shop1/stop", host))).andExpect(status().isNoContent());
        mvc.perform(op.write(post("/api/hosts/{h}/containers/shop1/restart", host))).andExpect(status().isNoContent());
        mvc.perform(op.write(post("/api/hosts/{h}/containers/shop1/pause", host))).andExpect(status().isNoContent());
        mvc.perform(op.write(post("/api/hosts/{h}/containers/shop1/unpause", host))).andExpect(status().isNoContent());
        mvc.perform(op.write(post("/api/hosts/{h}/containers/shop1/kill", host))).andExpect(status().isNoContent());
        verify(containers).startContainer(any(), any(), eq("shop1"));

        mvc.perform(op.write(post("/api/hosts/{h}/containers/blog1/start", host))).andExpect(status().isNotFound());
        mvc.perform(op.write(post("/api/hosts/{h}/containers/plain1/start", host))).andExpect(status().isNotFound());
        verify(containers, never()).startContainer(any(), any(), eq("blog1"));
        verify(containers, never()).startContainer(any(), any(), eq("plain1"));
    }

    @Test
    void hostViewPlusDeploymentOperateStaysForbiddenForOtherContainers() throws Exception {
        grantHost("operator", host, "VIEW");
        grantDeployment("operator", shop, "OPERATE");
        var op = login("operator");

        mvc.perform(op.write(post("/api/hosts/{h}/containers/shop1/start", host))).andExpect(status().isNoContent());
        mvc.perform(op.write(post("/api/hosts/{h}/containers/blog1/start", host))).andExpect(status().isForbidden());
        verify(containers, never()).startContainer(any(), any(), eq("blog1"));
    }

    @Test
    void deploymentViewGrantCannotAct() throws Exception {
        grantDeployment("operator", shop, "VIEW");
        mvc.perform(login("operator").write(post("/api/hosts/{h}/containers/shop1/start", host)))
                .andExpect(status().isForbidden());
        verify(containers, never()).startContainer(any(), any(), any());
    }

    @Test
    void hostOperateStillActsOnAnyContainer() throws Exception {
        grantHost("operator", host, "OPERATE");
        mvc.perform(login("operator").write(post("/api/hosts/{h}/containers/plain1/start", host)))
                .andExpect(status().isNoContent());
    }

    @Test
    void viewerWithDeploymentOperateGrantCannotAct() throws Exception {
        grantDeployment("viewer", shop, "OPERATE");
        mvc.perform(login("viewer").write(post("/api/hosts/{h}/containers/shop1/start", host)))
                .andExpect(status().isForbidden());
        verify(containers, never()).startContainer(any(), any(), any());
    }

    @Test
    void hostViewOnlyUserIsRejectedWithoutInspectingTheContainer() throws Exception {
        grantHost("operator", host, "VIEW");
        mvc.perform(login("operator").write(post("/api/hosts/{h}/containers/shop1/start", host)))
                .andExpect(status().isForbidden());
        verify(containers, never()).inspect(any(), any(), any());
        verify(containers, never()).startContainer(any(), any(), any());
    }

    @Test
    void missingAndNotVisibleContainersAnswerIdenticallyForDeploymentOnlyUsers() throws Exception {
        grantDeployment("operator", shop, "OPERATE");
        var op = login("operator");

        String missing = mvc.perform(op.write(post("/api/hosts/{h}/containers/missing1/kill", host)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String foreign = mvc.perform(op.write(post("/api/hosts/{h}/containers/blog1/kill", host)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String unmanaged = mvc.perform(op.write(post("/api/hosts/{h}/containers/plain1/kill", host)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(foreign).isEqualTo(missing);
        org.assertj.core.api.Assertions.assertThat(unmanaged).isEqualTo(missing);
        verify(containers, never()).killContainer(any(), any(), any());
    }

    @Test
    void daemonErrorsOtherThanNotFoundDoNotTellMissingFromForeign() throws Exception {
        grantDeployment("operator", shop, "OPERATE");
        when(containers.inspect(any(), any(), eq("odd1")))
                .thenThrow(new DockerConnectionException(DockerErrorCode.INVALID_STATE, "bad id", null));
        var op = login("operator");

        String odd = mvc.perform(op.write(post("/api/hosts/{h}/containers/odd1/kill", host)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String foreign = mvc.perform(op.write(post("/api/hosts/{h}/containers/blog1/kill", host)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(odd).isEqualTo(foreign);
    }
}
