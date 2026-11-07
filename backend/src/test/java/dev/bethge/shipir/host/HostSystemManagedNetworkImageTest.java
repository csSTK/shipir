package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.CreatedResourceDto;
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
 * The networks and images of the system-managed proxy can not be removed (or its network squatted) by a non-ADMIN:
 * that would break the proxy until an ADMIN repairs it.
 */
class HostSystemManagedNetworkImageTest extends HostApiTestBase {

    private static final String PROXY_IMAGE_ID = "sha256:aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String PLAIN_IMAGE_ID = "sha256:bbbbbbbbbbbbbbbbbbbbbbbb";

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
        when(containers.listContainers(any(), any())).thenReturn(List.of(
                new ContainerDto("px1", "proxy", "traefik:3", "running", "Up", null, null, List.of(), "shipir-proxy", "traefik")));
        when(containers.inspect(any(), any(), eq("px1"))).thenReturn(new ContainerDetailsDto("px1", "proxy", "traefik:3",
                "running", null, null, 0, null, List.of(), List.of(), false, false, List.of(), List.of(), Map.of(),
                List.of(new ContainerDetailsDto.Network("edge_net", null, null, null, List.of())), List.of(), null,
                "shipir-proxy", "traefik"));
        when(networks.list(any(), any())).thenReturn(List.of(
                new NetworkDto("net1111111111", "shipir-proxy", "bridge", "local", null, false, 1),
                new NetworkDto("net2222222222", "edge_net", "bridge", "local", null, false, 1),
                new NetworkDto("net3333333333", "shipir-proxy_default", "bridge", "local", null, false, 0),
                new NetworkDto("net4444444444", "plain", "bridge", "local", null, false, 0)));
        when(images.list(any(), any())).thenReturn(List.of(
                new ImageDto(PROXY_IMAGE_ID, List.of("traefik:3"), 10L, null, true),
                new ImageDto(PLAIN_IMAGE_ID, List.of("nginx:1"), 10L, null, false)));
        when(networks.createNetwork(any(), any(), any())).thenReturn(new CreatedResourceDto("n9", "x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"net1111111111", "net2222222222", "net3333333333", "shipir-proxy", "edge_net", "net1111"})
    void operatorCanNotRemoveANetworkOfTheProxy(String network) throws Exception {
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/networks/{n}", host, network)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(networks, never()).removeNetwork(any(), any(), any());
    }

    @Test
    void operatorMayRemoveAnOrdinaryNetworkAndAdminMayRemoveTheProxyNetwork() throws Exception {
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/networks/{n}", host, "net4444444444")))
                .andExpect(status().isNoContent());
        mvc.perform(login("admin").write(delete("/api/hosts/{h}/networks/{n}", host, "net1111111111")))
                .andExpect(status().isNoContent());
        verify(networks).removeNetwork(any(), any(), eq("net4444444444"));
        verify(networks).removeNetwork(any(), any(), eq("net1111111111"));
    }

    @ParameterizedTest
    @ValueSource(strings = {PROXY_IMAGE_ID, "traefik:3", "sha256:aaaa", "aaaa", "aaaaaaaaaaa", "aaaaaaaaaaaa"})
    void operatorCanNotRemoveAnImageOfTheProxy(String image) throws Exception {
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", host, image)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(images, never()).removeImage(any(), any(), any());
    }

    @Test
    void imagePulledByDigestIsProtectedForRemovalAndPrune() throws Exception {
        String digest = "sha256:" + "d".repeat(64);
        String digestImageId = "sha256:cccccccccccccccccccccccc";
        when(containers.listContainers(any(), any())).thenReturn(List.of(
                new ContainerDto("px1", "proxy", "traefik@" + digest, "running", "Up", null, null, List.of(), "shipir-proxy", "traefik")));
        when(images.list(any(), any())).thenReturn(List.of(
                new ImageDto(digestImageId, List.of(), 10L, null, true, List.of("traefik@" + digest), false),
                new ImageDto(PLAIN_IMAGE_ID, List.of("nginx:1"), 10L, null, false)));
        when(images.listPrunable(any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(List.of(
                new PruneCandidateDto(digestImageId, null, 5L, null),
                new PruneCandidateDto(PLAIN_IMAGE_ID, "nginx:1", 5L, null)));

        for (String ref : List.of(digestImageId, "sha256:cccc")) {
            mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", host, ref)))
                    .andExpect(status().isForbidden());
        }
        verify(images, never()).removeImage(any(), any(), any());
        mvc.perform(login("operator").write(post("/api/hosts/{h}/prune", host))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"images\":\"UNUSED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.images.removed").value(1));
        verify(images, never()).removeImage(any(), any(), eq(digestImageId));
    }

    @Test
    void operatorMayRemoveAnOrdinaryImageAndAdminMayRemoveTheProxyImage() throws Exception {
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", host, PLAIN_IMAGE_ID)))
                .andExpect(status().isNoContent());
        mvc.perform(login("admin").write(delete("/api/hosts/{h}/images/{i}", host, PROXY_IMAGE_ID)))
                .andExpect(status().isNoContent());
        verify(images).removeImage(any(), any(), eq(PLAIN_IMAGE_ID));
        verify(images).removeImage(any(), any(), eq(PROXY_IMAGE_ID));
    }

    @Test
    void operatorCanNotCreateANetworkNamedLikeAProxyNetworkButAdminAndOrdinaryNamesWork() throws Exception {
        for (String name : List.of("shipir-proxy", "edge_net", "shipir-proxy_default")) {
            mvc.perform(login("operator").write(post("/api/hosts/{h}/networks", host))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                    .andExpect(status().isForbidden());
        }
        verify(networks, never()).createNetwork(any(), any(), any());
        mvc.perform(login("operator").write(post("/api/hosts/{h}/networks", host))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"plain2\"}")).andExpect(status().isCreated());
        mvc.perform(login("admin").write(post("/api/hosts/{h}/networks", host))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"shipir-proxy\"}")).andExpect(status().isCreated());
    }

    @Test
    void pruneByOperatorSkipsProxyNetworksAndImagesButAdminPrunesThem() throws Exception {
        when(networks.listPrunable(any(), any())).thenReturn(List.of(
                new PruneCandidateDto("net1111111111", "shipir-proxy", null, null),
                new PruneCandidateDto("net4444444444", "plain", null, null)));
        when(images.listPrunable(any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(List.of(
                new PruneCandidateDto(PROXY_IMAGE_ID, "traefik:3", 5L, null),
                new PruneCandidateDto(PLAIN_IMAGE_ID, "nginx:1", 5L, null)));
        String body = "{\"networks\":true,\"images\":\"UNUSED\"}";

        mvc.perform(login("operator").write(post("/api/hosts/{h}/prune", host))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.networks.removed").value(1))
                .andExpect(jsonPath("$.images.removed").value(1));
        verify(networks, never()).removeNetwork(any(), any(), eq("net1111111111"));
        verify(images, never()).removeImage(any(), any(), eq(PROXY_IMAGE_ID));

        mvc.perform(login("admin").write(post("/api/hosts/{h}/prune", host))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.networks.removed").value(2))
                .andExpect(jsonPath("$.images.removed").value(2));
    }

    @Test
    void failsClosedWhenDockerCanNotBeAskedWhichResourcesBelongToTheProxy() throws Exception {
        when(containers.listContainers(any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/networks/{n}", host, "net4444444444")))
                .andExpect(status().isBadGateway());
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", host, PLAIN_IMAGE_ID)))
                .andExpect(status().isBadGateway());
        verify(networks, never()).removeNetwork(any(), any(), any());
        verify(images, never()).removeImage(any(), any(), any());
    }
}
