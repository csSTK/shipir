package dev.bethge.shipir.host;

import dev.bethge.shipir.compose.execution.RegistryAuthSource;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.DockerSystemService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.DeploymentSummaryDto;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.host.dto.HostDeploymentResponse;
import dev.bethge.shipir.permission.CurrentAccess;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code updateAvailable} on images and deployments is filled from the update store (stubbed ports, no Spring). */
class HostImageUpdateFlagTest {

    private final UUID hostId = UUID.randomUUID();
    private final ImageUpdateStore store = new ImageUpdateStore();
    private final ContainerService containers = mock(ContainerService.class);
    private final ImageService images = mock(ImageService.class);
    private final HostResourcesService service;

    HostImageUpdateFlagTest() {
        DockerHostRepository hosts = mock(DockerHostRepository.class);
        DockerHost host = mock(DockerHost.class);
        when(host.getId()).thenReturn(hostId);
        when(hosts.findById(hostId)).thenReturn(java.util.Optional.of(host));
        HostEndpointResolver endpoints = mock(HostEndpointResolver.class);
        ManagedDeploymentLookup managed = mock(ManagedDeploymentLookup.class);
        when(managed.managedDeploymentIds(hostId)).thenReturn(Map.of());
        CurrentAccess access = mock(CurrentAccess.class);
        when(access.canView(hostId)).thenReturn(true);
        when(access.hostFullyVisible(hostId)).thenReturn(true);
        when(access.visibleDeploymentIds(hostId)).thenReturn(java.util.Optional.empty());
        service = new HostResourcesService(hosts, endpoints, containers, images, null, null, managed, access,
                null, mock(RegistryAuthSource.class), store, mock(DockerSystemService.class), null, mock(SystemManagedGuard.class));
    }

    private static ContainerDto container(String image, String project) {
        return new ContainerDto("c", "n", image, "running", "Up", null, Instant.EPOCH, List.of(), project, "web");
    }

    @Test
    void imagesCarryUpdateFlagFromStore() {
        store.replace(hostId, Set.of("docker.io/library/nginx:latest"), Instant.EPOCH);
        when(images.list(any(), any())).thenReturn(List.of(
                new ImageDto("a", List.of("nginx:latest"), 1L, null, true),
                new ImageDto("b", List.of("redis:7"), 1L, null, true)));
        assertThat(service.images(hostId)).extracting(ImageDto::updateAvailable).containsExactly(true, false);
    }

    @Test
    void deploymentIsFlaggedWhenOneOfItsContainersRunsAnOutdatedImage() {
        store.replace(hostId, Set.of("docker.io/library/nginx:latest"), Instant.EPOCH);
        when(containers.listDeployments(any(), any())).thenReturn(List.of(
                new DeploymentSummaryDto("web", 2, 2, DeploymentSummaryDto.Status.RUNNING, null),
                new DeploymentSummaryDto("db", 1, 1, DeploymentSummaryDto.Status.RUNNING, null)));
        when(containers.listContainers(any(), any())).thenReturn(List.of(
                container("nginx", "web"), container("redis:7", "web"), container("postgres:16", "db")));
        assertThat(service.deployments(hostId)).extracting(HostDeploymentResponse::name,
                HostDeploymentResponse::updateAvailable).containsExactly(
                org.assertj.core.groups.Tuple.tuple("web", true), org.assertj.core.groups.Tuple.tuple("db", false));
    }

    @Test
    void flagIsPerDeploymentWhenASiblingAlreadyPulledTheTag() {
        // No outdated digest is known (the sibling pulled the tag), but "web" still runs the previous image:
        // Docker shows the image ID for it, "db" runs the current one.
        String oldId = "sha256:0123456789abcdef0123456789abcdef";
        when(containers.listDeployments(any(), any())).thenReturn(List.of(
                new DeploymentSummaryDto("web", 1, 1, DeploymentSummaryDto.Status.RUNNING, null),
                new DeploymentSummaryDto("db", 1, 1, DeploymentSummaryDto.Status.RUNNING, null)));
        when(containers.listContainers(any(), any())).thenReturn(List.of(
                new ContainerDto("c1", "n1", "0123456789ab", "running", "Up", null, Instant.EPOCH, List.of(), "web",
                        "web", oldId),
                new ContainerDto("c2", "n2", "nginx:latest", "running", "Up", null, Instant.EPOCH, List.of(), "db",
                        "db", "sha256:fedcba9876543210fedcba9876543210")));
        assertThat(service.deployments(hostId)).extracting(HostDeploymentResponse::name,
                HostDeploymentResponse::updateAvailable).containsExactly(
                org.assertj.core.groups.Tuple.tuple("web", true), org.assertj.core.groups.Tuple.tuple("db", false));
    }

    @Test
    void deploymentsWithoutAnyUpdateStayUnflagged() {
        when(containers.listDeployments(any(), any())).thenReturn(List.of(
                new DeploymentSummaryDto("web", 1, 1, DeploymentSummaryDto.Status.RUNNING, null)));
        when(containers.listContainers(any(), any())).thenReturn(List.of(container("nginx", "web")));
        assertThat(service.deployments(hostId)).extracting(HostDeploymentResponse::updateAvailable)
                .containsExactly(false);
    }
}
