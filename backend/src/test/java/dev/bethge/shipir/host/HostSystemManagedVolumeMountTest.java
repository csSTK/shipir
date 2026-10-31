package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.dto.CreatedResourceDto;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.VolumeDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The volumes of the system-managed proxy can not be mounted into a new container by a non-ADMIN (that would expose
 * the certificate keys and DNS config through logs or writes), nor be deleted by one.
 */
class HostSystemManagedVolumeMountTest extends HostApiTestBase {

    @MockitoBean ContainerService containers;
    @MockitoBean ImageService images;
    @MockitoBean NetworkService networks;
    @MockitoBean VolumeService volumes;

    private String host;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        UUID hostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        host = hostId.toString();
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by, system_managed) "
                + "SELECT 'shipir-proxy', ?, 'x', id, TRUE FROM users WHERE username = 'admin'", hostId);
        when(volumes.list(any(), any())).thenReturn(List.of(
                new VolumeDto("proxy_certs", "local", "/v/certs", null, true, "shipir-proxy"),
                new VolumeDto("plain", "local", "/v/plain", null, false, null)));
        when(containers.listContainers(any(), any())).thenReturn(List.of(
                new ContainerDto("px1", "proxy", "traefik:3", "running", "Up", null, null, List.of(), "shipir-proxy", "traefik")));
        when(containers.inspect(any(), any(), eq("px1"))).thenReturn(details("px1", "shipir-proxy",
                new ContainerDetailsDto.Mount("volume", "mounted_conf", null, "/conf", false)));
        when(containers.inspect(any(), any(), eq("web1"))).thenReturn(details("web1", null));
        when(containers.createContainer(any(), any(), any())).thenReturn(new CreatedResourceDto("n1", "x"));
        when(containers.recreateContainer(any(), any(), eq("web1"), any())).thenReturn(new CreatedResourceDto("n2", "web"));
    }

    private static ContainerDetailsDto details(String id, String project, ContainerDetailsDto.Mount... mounts) {
        return new ContainerDetailsDto(id, "web", "nginx", "running", null, null, 0, null, List.of(), List.of(), false,
                false, List.of(), List.of(mounts), Map.of(), List.of(), List.of(), null, project, project == null ? null : "svc");
    }

    private static String spec(String volume) {
        return "{\"image\":\"nginx\",\"mounts\":[{\"type\":\"VOLUME\",\"source\":\"" + volume + "\",\"target\":\"/data\"}]}";
    }

    @Test
    void operatorCanNotMountAProxyVolumeIntoACreatedDuplicatedOrRecreatedContainer() throws Exception {
        Login op = login("operator");
        for (String volume : List.of("proxy_certs", "mounted_conf")) {
            for (String path : List.of("", "/web1/duplicate", "/web1/recreate")) {
                mvc.perform(op.write(post("/api/hosts/{h}/containers" + path, host))
                                .contentType(MediaType.APPLICATION_JSON).content(spec(volume)))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            }
        }
        verify(containers, never()).createContainer(any(), any(), any());
        verify(containers, never()).recreateContainer(any(), any(), any(), any());
    }

    @Test
    void adminMayMountAProxyVolume() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts/{h}/containers", host)).contentType(MediaType.APPLICATION_JSON)
                .content(spec("proxy_certs"))).andExpect(status().isCreated());
        mvc.perform(admin.write(post("/api/hosts/{h}/containers/web1/recreate", host)).contentType(MediaType.APPLICATION_JSON)
                .content(spec("proxy_certs"))).andExpect(status().isOk());
    }

    @Test
    void operatorMayStillMountAnOrdinaryVolume() throws Exception {
        Login op = login("operator");
        mvc.perform(op.write(post("/api/hosts/{h}/containers", host)).contentType(MediaType.APPLICATION_JSON)
                .content(spec("plain"))).andExpect(status().isCreated());
        mvc.perform(op.write(post("/api/hosts/{h}/containers/web1/duplicate", host)).contentType(MediaType.APPLICATION_JSON)
                .content(spec("plain"))).andExpect(status().isCreated());
        mvc.perform(op.write(post("/api/hosts/{h}/containers/web1/recreate", host)).contentType(MediaType.APPLICATION_JSON)
                .content(spec("plain"))).andExpect(status().isOk());
    }

    @Test
    void operatorCanNotDeleteAProxyVolumeButAdminAndOrdinaryVolumesWork() throws Exception {
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/volumes/proxy_certs", host)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/volumes/mounted_conf", host)))
                .andExpect(status().isForbidden());
        verify(volumes, never()).removeVolume(any(), any(), any());
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/volumes/plain", host))).andExpect(status().isNoContent());
        mvc.perform(login("admin").write(delete("/api/hosts/{h}/volumes/proxy_certs", host))).andExpect(status().isNoContent());
        verify(volumes).removeVolume(any(), any(), eq("plain"));
        verify(volumes).removeVolume(any(), any(), eq("proxy_certs"));
    }
}
