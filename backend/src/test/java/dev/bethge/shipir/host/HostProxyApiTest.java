package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/hosts/{h}/proxy against a stubbed ContainerService. */
class HostProxyApiTest extends HostApiTestBase {

    @MockitoBean
    ContainerService containers;

    @Override
    protected boolean autoGrantHosts() {
        return false;
    }

    private String localHostId() {
        SetupGateFixture.resetWithAdminHost(jdbc, "local");
        return jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    private void stubTraefik() {
        when(containers.listContainers(any(), any())).thenReturn(List.of(
                new ContainerDto("t1", "traefik", "traefik:v3.1", "running", "Up", null, null, List.of(), null, null)));
        when(containers.inspect(any(), any(), eq("t1"))).thenReturn(new ContainerDetailsDto("t1", "traefik",
                "traefik:v3.1", "running", null, null, 0, null, List.of(), List.of(), false, false, List.of(),
                List.of(), Map.of(), List.of(new ContainerDetailsDto.Network("proxy", null, null, null, List.of())),
                List.of(), null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "operator", "admin"})
    void everyRoleSeesTheDetectedProxy(String user) throws Exception {
        String host = localHostId();
        grantAllHosts();
        stubTraefik();
        mvc.perform(get("/api/hosts/{h}/proxy", host).session(login(user).session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visible").value(true))
                .andExpect(jsonPath("$.proxy").value("TRAEFIK"))
                .andExpect(jsonPath("$.container").value("traefik"))
                .andExpect(jsonPath("$.network").value("proxy"));
    }

    @Test
    void deploymentOnlyGrantGetsNoProxyAndNoDockerCall() throws Exception {
        String host = localHostId();
        stubTraefik();
        UUID dep = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'd', ?::uuid, 'x', id FROM users WHERE username = 'admin' RETURNING id", UUID.class, host);
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, 'OPERATE' "
                + "FROM users WHERE username = 'operator'", dep);
        mvc.perform(get("/api/hosts/{h}/proxy", host).session(login("operator").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visible").value(false))
                .andExpect(jsonPath("$.proxy").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.network").value(org.hamcrest.Matchers.nullValue()));
        verify(containers, never()).listContainers(any(), any());
    }

    @Test
    void noProxyGivesNullFields() throws Exception {
        String host = localHostId();
        when(containers.listContainers(any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/hosts/{h}/proxy", host).session(login("admin").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visible").value(true))
                .andExpect(jsonPath("$.proxy").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.network").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void unknownHostIs404() throws Exception {
        mvc.perform(get("/api/hosts/{h}/proxy", UUID.randomUUID()).session(login("viewer").session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/api/hosts/{h}/proxy", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void unreachableDockerIs502() throws Exception {
        String host = localHostId();
        when(containers.listContainers(any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        mvc.perform(get("/api/hosts/{h}/proxy", host).session(login("admin").session()))
                .andExpect(status().isBadGateway());
    }
}
