package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.CreatedResourceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Remove, recreate, duplicate and resource edits of a container stay host-level OPERATE: they change the container
 * outside Compose, so a deployment-level OPERATE grant (which covers start/stop/restart/pause/unpause/kill only, see
 * {@link HostContainerLifecycleAccessTest}) is not enough, for its own containers as well as for foreign ones.
 * Decision recorded in shipr-0qbh.54. The denial tests pin the existing behaviour and pass as soon as they are written.
 */
class HostResourceActionsAccessTest extends HostApiTestBase {

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
        when(containers.recreateContainer(any(), any(), any(), any())).thenReturn(new CreatedResourceDto("new1", "plain1"));
        when(containers.createContainer(any(), any(), any())).thenReturn(new CreatedResourceDto("new1", "plain1"));
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

    private MockHttpServletRequestBuilder remove(Login l, String cid) {
        return l.write(delete("/api/hosts/{h}/containers/{c}", host, cid));
    }

    private MockHttpServletRequestBuilder action(Login l, String cid, String action) {
        return l.write(post("/api/hosts/{h}/containers/{c}/{a}", host, cid, action))
                .contentType(MediaType.APPLICATION_JSON).content(json("{'image':'nginx:1'}"));
    }

    private MockHttpServletRequestBuilder resources(Login l, String cid) {
        return l.write(patch("/api/hosts/{h}/containers/{c}/resources", host, cid))
                .contentType(MediaType.APPLICATION_JSON).content(json("{'cpus':0.5}"));
    }

    @Test
    void deploymentOperateGrantIsRejectedForRemoveRecreateDuplicateAndResources() throws Exception {
        grantDeployment("operator", shop, "OPERATE");
        var op = login("operator");

        for (String cid : List.of("shop1", "blog1")) {
            mvc.perform(remove(op, cid)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(action(op, cid, "recreate")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(action(op, cid, "duplicate")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(resources(op, cid)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        verify(containers, never()).removeContainer(any(), any(), any(), anyBoolean());
        verify(containers, never()).recreateContainer(any(), any(), any(), any());
        verify(containers, never()).createContainer(any(), any(), any());
        verify(containers, never()).updateResources(any(), any(), any(), any());
    }

    @Test
    void hostOperateStillAllowsRemoveRecreateDuplicateAndResources() throws Exception {
        grantHost("operator", host, "OPERATE");
        var op = login("operator");

        mvc.perform(remove(op, "plain1")).andExpect(status().isNoContent());
        mvc.perform(action(op, "plain1", "recreate")).andExpect(status().isOk());
        mvc.perform(action(op, "plain1", "duplicate")).andExpect(status().isCreated());
        mvc.perform(resources(op, "plain1")).andExpect(status().isNoContent());

        verify(containers).removeContainer(any(), any(), eq("plain1"), anyBoolean());
        verify(containers).recreateContainer(any(), any(), eq("plain1"), any());
        verify(containers).createContainer(any(), any(), any());
        verify(containers).updateResources(any(), any(), eq("plain1"), any());
    }
}
