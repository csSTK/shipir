package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.docker.dto.NetworkDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An ADMIN-customised proxy image or extra network (or the previous image after a template bump) is protected from
 * non-ADMINs while the proxy is stopped: the guard derives them from the proxy deployment's stored compose file.
 */
class HostSystemManagedCustomisedProxyTest extends HostApiTestBase {

    private static final String CUSTOM_ID = "sha256:eeeeeeeeeeeeeeeeeeeeeeee";
    private static final String PLAIN_ID = "sha256:bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String COMPOSE = """
            services:
              proxy:
                image: my-traefik:9.9
                networks: [edge]
            networks:
              edge:
                name: custom-edge
            """;

    @MockitoBean ContainerService containers;
    @MockitoBean ImageService images;
    @MockitoBean NetworkService networks;
    @MockitoBean VolumeService volumes;
    @Autowired ComposeFileRepository composeFiles;

    private UUID hostId;

    @BeforeEach
    void setUp() {
        SetupGateFixture.resetWithAdminHost(jdbc, "local");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by, system_managed) "
                + "SELECT 'shipir-proxy', ?, 'x', id, TRUE FROM users WHERE username = 'admin'", hostId);
        composeFiles.write(hostId, "shipir-proxy", COMPOSE, "");
        when(containers.listContainers(any(), any())).thenReturn(List.of());
        when(networks.list(any(), any())).thenReturn(List.of(
                new NetworkDto("net5555555555", "custom-edge", "bridge", "local", null, false, 0),
                new NetworkDto("net4444444444", "plain", "bridge", "local", null, false, 0)));
        when(images.list(any(), any())).thenReturn(List.of(
                new ImageDto(CUSTOM_ID, List.of("my-traefik:9.9"), 10L, null, false),
                new ImageDto(PLAIN_ID, List.of("nginx:1"), 10L, null, false)));
    }

    @AfterEach
    void cleanUp() {
        composeFiles.delete(hostId, "shipir-proxy");
    }

    @Test
    void operatorCanNotRemoveACustomisedProxyImageWhileTheProxyIsStopped() throws Exception {
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", hostId, "my-traefik:9.9")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(images, never()).removeImage(any(), any(), any());
    }

    @Test
    void operatorCanNotRemoveACustomProxyNetworkWhileTheProxyIsStopped() throws Exception {
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/networks/{n}", hostId, "custom-edge")))
                .andExpect(status().isForbidden());
        verify(networks, never()).removeNetwork(any(), any(), any());
    }

    @Test
    void operatorStillRemovesAnOrdinaryImageAndAdminMayRemoveTheCustomisedOne() throws Exception {
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", hostId, PLAIN_ID)))
                .andExpect(status().isNoContent());
        mvc.perform(login("admin").write(delete("/api/hosts/{h}/images/{i}", hostId, CUSTOM_ID)))
                .andExpect(status().isNoContent());
    }
}
