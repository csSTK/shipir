package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.docker.dto.NetworkDto;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
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
 * After {@code compose down} the proxy has no containers, yet its image and network must stay protected from
 * non-ADMINs: they are recognised from the bundled proxy templates, not from running containers.
 */
class HostSystemManagedStoppedProxyTest extends HostApiTestBase {

    private static final String CADDY_ID = "sha256:cccccccccccccccccccccccc";
    private static final String TRAEFIK_ID = "sha256:dddddddddddddddddddddddd";
    private static final String PLAIN_ID = "sha256:bbbbbbbbbbbbbbbbbbbbbbbb";

    @MockitoBean ContainerService containers;
    @MockitoBean ImageService images;
    @MockitoBean NetworkService networks;
    @MockitoBean VolumeService volumes;

    private String host;

    @BeforeEach
    void setUp() {
        SetupGateFixture.resetWithAdminHost(jdbc, "local");
        UUID hostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        host = hostId.toString();
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by, system_managed) "
                + "SELECT 'shipir-proxy', ?, 'x', id, TRUE FROM users WHERE username = 'admin'", hostId);
        when(containers.listContainers(any(), any())).thenReturn(List.of());
        when(networks.list(any(), any())).thenReturn(List.of(
                new NetworkDto("net1111111111", "shipir-proxy", "bridge", "local", null, false, 0),
                new NetworkDto("net4444444444", "plain", "bridge", "local", null, false, 0)));
        when(images.list(any(), any())).thenReturn(List.of(
                new ImageDto(CADDY_ID, List.of("caddy:2.10.2-alpine"), 10L, null, false),
                new ImageDto(TRAEFIK_ID, List.of("traefik:v3.5.3"), 10L, null, false),
                new ImageDto(PLAIN_ID, List.of("nginx:1"), 10L, null, false)));
    }

    @ParameterizedTest
    @ValueSource(strings = {CADDY_ID, TRAEFIK_ID, "caddy:2.10.2-alpine", "traefik:v3.5.3"})
    void operatorCanNotRemoveTheProxyImageWhileTheProxyIsStopped(String image) throws Exception {
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", host, image)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(images, never()).removeImage(any(), any(), any());
    }

    @Test
    void operatorCanNotRemoveTheProxyNetworkWhileTheProxyIsStopped() throws Exception {
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/networks/{n}", host, "shipir-proxy")))
                .andExpect(status().isForbidden());
        verify(networks, never()).removeNetwork(any(), any(), any());
    }

    @Test
    void ordinaryImageStaysRemovableAndAdminMayRemoveTheProxyImage() throws Exception {
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", host, PLAIN_ID)))
                .andExpect(status().isNoContent());
        mvc.perform(login("admin").write(delete("/api/hosts/{h}/images/{i}", host, CADDY_ID)))
                .andExpect(status().isNoContent());
        verify(images).removeImage(any(), any(), eq(PLAIN_ID));
        verify(images).removeImage(any(), any(), eq(CADDY_ID));
    }

    @Test
    void pruneByOperatorSkipsTheStoppedProxyImage() throws Exception {
        when(images.listPrunable(any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(List.of(
                new PruneCandidateDto(CADDY_ID, "caddy:2.10.2-alpine", 5L, null),
                new PruneCandidateDto(PLAIN_ID, "nginx:1", 5L, null)));
        mvc.perform(login("operator").write(post("/api/hosts/{h}/prune", host))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"images\":\"UNUSED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.images.removed").value(1));
        verify(images, never()).removeImage(any(), any(), eq(CADDY_ID));
    }
}
