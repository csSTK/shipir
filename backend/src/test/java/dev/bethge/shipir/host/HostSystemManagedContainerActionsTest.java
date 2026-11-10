package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Container actions on the containers of the system-managed proxy deployment are ADMIN-only, even with host OPERATE. */
class HostSystemManagedContainerActionsTest extends HostApiTestBase {

    @MockitoBean ContainerService containers;
    @MockitoBean ImageService images;
    @MockitoBean NetworkService networks;
    @MockitoBean VolumeService volumes;

    private UUID host;
    private UUID proxy;

    @Override
    protected boolean autoGrantHosts() {
        return false;
    }

    @BeforeEach
    void setUp() {
        SetupGateFixture.resetWithAdminHost(jdbc, "local");
        host = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        proxy = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by, system_managed) "
                + "SELECT 'shipir-proxy', ?, 'x', id, TRUE FROM users WHERE username = 'admin' RETURNING id", UUID.class, host);
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'shop', ?, 'x', id FROM users WHERE username = 'admin'", host);
        when(containers.inspect(any(), any(), eq("proxy1"))).thenReturn(details("shipir-proxy"));
        when(containers.inspect(any(), any(), eq("shop1"))).thenReturn(details("shop"));
        grantHost("operator", host, "OPERATE");
    }

    private static ContainerDetailsDto details(String project) {
        return new ContainerDetailsDto("id1", "web", "nginx:1", "running", null, null, 0,
                new ContainerDetailsDto.RestartPolicy("no", 0), List.of("nginx"), List.of(), false, false, List.of(),
                List.of(), Map.of(), List.of(), List.of(), null, project, "web");
    }

    private static final String SPEC = "{\"image\":\"nginx:1\"}";

    @Test
    void operatorIsForbiddenOnEveryActionOnAProxyContainer() throws Exception {
        var op = login("operator");
        for (String action : List.of("start", "stop", "restart", "pause", "unpause", "kill")) {
            mvc.perform(op.write(post("/api/hosts/{h}/containers/proxy1/" + action, host)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(op.write(delete("/api/hosts/{h}/containers/proxy1", host)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        for (String action : List.of("recreate", "duplicate")) {
            mvc.perform(op.write(post("/api/hosts/{h}/containers/proxy1/" + action, host))
                            .contentType(MediaType.APPLICATION_JSON).content(SPEC))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(op.write(patch("/api/hosts/{h}/containers/proxy1/resources", host))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"restartPolicy\":\"always\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(containers, never()).startContainer(any(), any(), any());
        verify(containers, never()).stopContainer(any(), any(), any(), anyInt());
        verify(containers, never()).killContainer(any(), any(), any());
        verify(containers, never()).removeContainer(any(), any(), any(), anyBoolean());
        verify(containers, never()).recreateContainer(any(), any(), any(), any());
        verify(containers, never()).updateResources(any(), any(), any(), any());
    }

    @Test
    void deploymentOperateGrantOnTheProxyDoesNotHelpEither() throws Exception {
        jdbc.update("DELETE FROM permissions");
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, 'OPERATE' "
                + "FROM users WHERE username = 'operator'", proxy);
        mvc.perform(login("operator").write(post("/api/hosts/{h}/containers/proxy1/stop", host)))
                .andExpect(status().isForbidden());
        verify(containers, never()).stopContainer(any(), any(), any(), anyInt());
    }

    @Test
    void adminActsOnAProxyContainer() throws Exception {
        var admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts/{h}/containers/proxy1/restart", host))).andExpect(status().isNoContent());
        mvc.perform(admin.write(post("/api/hosts/{h}/containers/proxy1/kill", host))).andExpect(status().isNoContent());
        mvc.perform(admin.write(delete("/api/hosts/{h}/containers/proxy1", host))).andExpect(status().isNoContent());
        verify(containers).killContainer(any(), any(), eq("proxy1"));
        verify(containers).removeContainer(any(), any(), eq("proxy1"), anyBoolean());
    }

    @Test
    void operatorStillActsOnOrdinaryContainers() throws Exception {
        var op = login("operator");
        mvc.perform(op.write(post("/api/hosts/{h}/containers/shop1/kill", host))).andExpect(status().isNoContent());
        mvc.perform(op.write(delete("/api/hosts/{h}/containers/shop1", host))).andExpect(status().isNoContent());
        verify(containers).killContainer(any(), any(), eq("shop1"));
    }

    @Test
    void hostDeploymentListFlagsTheProxyProjectForTheUi() throws Exception {
        when(containers.listDeployments(any(), any())).thenReturn(List.of(
                new dev.bethge.shipir.docker.dto.DeploymentSummaryDto("shipir-proxy", 1, 1,
                        dev.bethge.shipir.docker.dto.DeploymentSummaryDto.Status.RUNNING, null),
                new dev.bethge.shipir.docker.dto.DeploymentSummaryDto("shop", 1, 1,
                        dev.bethge.shipir.docker.dto.DeploymentSummaryDto.Status.RUNNING, null)));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/hosts/{h}/deployments", host).session(login("operator").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='shipir-proxy')].systemManaged").value(true))
                .andExpect(jsonPath("$[?(@.name=='shop')].systemManaged").value(false));
    }
}
