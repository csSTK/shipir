package dev.bethge.shipir.streaming;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ContainerStatsService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ContainerStatsDto;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Stats samples of a system-managed project (the managed proxy) go to ADMINs only, also on a fully visible host. */
class ContainerStatsSystemManagedTest {

    private final UUID hostId = UUID.randomUUID();
    private final ContainerService containers = mock(ContainerService.class);
    private final ContainerStatsService statsService = mock(ContainerStatsService.class);
    private final DockerHostRepository hosts = mock(DockerHostRepository.class);
    private final HostEndpointResolver endpoints = mock(HostEndpointResolver.class);
    private final StompFrameSender sender = mock(StompFrameSender.class);
    private final StreamAccess access = mock(StreamAccess.class);
    private ContainerStatsStreamService service;

    private static ContainerDto container(String id, String project) {
        ContainerDto c = mock(ContainerDto.class);
        when(c.id()).thenReturn(id);
        when(c.state()).thenReturn("running");
        when(c.composeProject()).thenReturn(project);
        return c;
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void viewerOfAFullyVisibleHostDoesNotGetTheProxyContainersSample() {
        when(hosts.findById(hostId)).thenReturn(Optional.of(mock(DockerHost.class)));
        when(endpoints.endpointOf(any())).thenReturn(new DockerEndpoint.Local("unix:///x"));
        List<ContainerDto> running = List.of(
                container("proxy1", "shipir-proxy"), container("web1", "shop"), container("plain1", null));
        when(containers.listContainers(eq(hostId), any())).thenReturn(running);
        when(statsService.snapshot(eq(hostId), any(), any())).thenReturn(mock(ContainerStatsDto.class));
        when(access.roleOf("root")).thenReturn(Optional.of(dev.bethge.shipir.auth.Role.ADMIN));
        when(access.roleOf("viewer")).thenReturn(Optional.of(dev.bethge.shipir.auth.Role.VIEWER));
        when(access.visibleProjects(any(), eq(hostId))).thenReturn(Optional.empty()); // whole host visible
        when(access.systemManagedProjects(hostId)).thenReturn(Set.of("shipir-proxy"));
        service = new ContainerStatsStreamService(containers, statsService, hosts, endpoints, sender,
                new ContainerStatsProperties(Duration.ofMillis(200), 50), access);

        service.subscribe(hostId, "viewer", "sv", "a");
        service.subscribe(hostId, "root", "sa", "b");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(idsSentTo("sa")).containsExactlyInAnyOrder("proxy1", "web1", "plain1");
            assertThat(idsSentTo("sv")).containsExactlyInAnyOrder("web1", "plain1");
        });
    }

    private List<String> idsSentTo(String session) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sender, atLeastOnce()).send(eq(session), any(), eq(ContainerStatsStreamService.destination(hostId)),
                payload.capture());
        return payload.getAllValues().stream().map(ContainerStatsMessage.class::cast)
                .flatMap(m -> m.samples().stream()).map(ContainerStatsMessage.Sample::containerId).distinct().toList();
    }

    @Test
    void demotedAdminLosesTheProxySamplesAndRawCodes() {
        when(hosts.findById(hostId)).thenReturn(Optional.of(mock(DockerHost.class)));
        when(endpoints.endpointOf(any())).thenReturn(new DockerEndpoint.Local("unix:///x"));
        List<ContainerDto> running = List.of(container("proxy1", "shipir-proxy"), container("web1", "shop"));
        when(containers.listContainers(eq(hostId), any())).thenReturn(running);
        when(statsService.snapshot(eq(hostId), any(), any())).thenReturn(mock(ContainerStatsDto.class));
        when(access.roleOf("root")).thenReturn(Optional.of(dev.bethge.shipir.auth.Role.ADMIN));
        when(access.visibleProjects(any(), eq(hostId))).thenReturn(Optional.empty());
        when(access.systemManagedProjects(hostId)).thenReturn(Set.of("shipir-proxy"));
        service = new ContainerStatsStreamService(containers, statsService, hosts, endpoints, sender,
                new ContainerStatsProperties(Duration.ofMillis(200), 50), access);
        service.subscribe(hostId, "root", "sa", "b");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(idsSentTo("sa")).containsExactlyInAnyOrder("proxy1", "web1"));

        service.refreshAdmin("root", false);
        org.mockito.Mockito.clearInvocations(sender);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(idsSentTo("sa")).containsExactly("web1"));
    }

    @Test
    void unreadableRoleIsTreatedAsNonAdmin() {
        when(hosts.findById(hostId)).thenReturn(Optional.of(mock(DockerHost.class)));
        when(endpoints.endpointOf(any())).thenReturn(new DockerEndpoint.Local("unix:///x"));
        List<ContainerDto> running = List.of(container("proxy1", "shipir-proxy"), container("web1", "shop"));
        when(containers.listContainers(eq(hostId), any())).thenReturn(running);
        when(statsService.snapshot(eq(hostId), any(), any())).thenReturn(mock(ContainerStatsDto.class));
        when(access.roleOf("root")).thenThrow(new IllegalStateException("db down"));
        when(access.visibleProjects(any(), eq(hostId))).thenReturn(Optional.empty());
        when(access.systemManagedProjects(hostId)).thenReturn(Set.of("shipir-proxy"));
        service = new ContainerStatsStreamService(containers, statsService, hosts, endpoints, sender,
                new ContainerStatsProperties(Duration.ofMillis(200), 50), access);

        service.subscribe(hostId, "root", "sa", "b");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(idsSentTo("sa")).containsExactly("web1"));
    }
}
