package dev.bethge.shipir.streaming;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import dev.bethge.shipir.host.ManagedDeploymentLookup;
import dev.bethge.shipir.permission.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Logs, stats, events and run output of system-managed deployments (the managed proxy) are ADMIN-only, whatever
 * the grants say: its logs can carry ACME / DNS provider error text.
 */
class StreamAccessSystemManagedStreamsTest {

    private final UUID host = UUID.randomUUID();
    private final UUID proxy = UUID.randomUUID();
    private final UUID shop = UUID.randomUUID();
    private final PermissionService permissions = mock(PermissionService.class);
    private final ContainerService containers = mock(ContainerService.class);
    private final DockerHostRepository hosts = mock(DockerHostRepository.class);
    private final HostEndpointResolver endpoints = mock(HostEndpointResolver.class);
    private StreamAccess access;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("viewer")).thenReturn(Optional.of(new User("viewer", "x", Role.VIEWER)));
        when(users.findByUsername("root")).thenReturn(Optional.of(new User("root", "x", Role.ADMIN)));
        // the viewer sees everything: the whole host and every deployment
        when(permissions.hostFullyVisible(any(), any())).thenReturn(true);
        when(permissions.canView(any(), any(UUID.class))).thenReturn(true);
        when(permissions.canView(any(), any(UUID.class), any(UUID.class))).thenReturn(true);
        when(permissions.visibleDeploymentIds(any(), any())).thenReturn(Set.of(proxy, shop));
        when(hosts.findById(host)).thenReturn(Optional.of(mock(DockerHost.class)));
        ManagedDeploymentLookup managed = new ManagedDeploymentLookup() {
            @Override public Map<String, UUID> managedDeploymentIds(UUID hostId) {
                return Map.of("shipir-proxy", proxy, "shop", shop);
            }
            @Override public Set<String> systemManagedNames(UUID hostId) {
                return Set.of("shipir-proxy");
            }
        };
        access = new StreamAccess(permissions, users, managed, containers, hosts, endpoints);
    }

    private void containerOfProject(String id, String project) {
        ContainerDetailsDto d = mock(ContainerDetailsDto.class);
        when(d.composeProject()).thenReturn(project);
        when(containers.inspect(eq(host), any(), eq(id))).thenReturn(d);
    }

    @Test
    void viewerMayNotFollowTheProxyProjectLogsEvenWithTheWholeHostVisible() {
        assertThat(access.mayFollowProject("viewer", host, "shipir-proxy")).isFalse();
    }

    @Test
    void viewerStillFollowsOrdinaryAndUnmanagedProjectLogs() {
        assertThat(access.mayFollowProject("viewer", host, "shop")).isTrue();
        assertThat(access.mayFollowProject("viewer", host, "adhoc")).isTrue();
    }

    @Test
    void viewerWithOnlyADeploymentGrantOnTheProxyMayNotFollowItsLogs() {
        when(permissions.hostFullyVisible(any(), any())).thenReturn(false);
        assertThat(access.mayFollowProject("viewer", host, "shipir-proxy")).isFalse();
        assertThat(access.mayFollowProject("viewer", host, "shop")).isTrue();
    }

    @Test
    void adminFollowsTheProxyProjectLogs() {
        assertThat(access.mayFollowProject("root", host, "shipir-proxy")).isTrue();
    }

    @Test
    void viewerMayNotFollowAProxyContainersLogsOnAFullyVisibleHost() {
        containerOfProject("proxy1", "shipir-proxy");
        assertThat(access.mayFollowContainer("viewer", host, "proxy1")).isFalse();
    }

    @Test
    void viewerFollowsOrdinaryAndUnlabelledContainersOnAFullyVisibleHost() {
        containerOfProject("web1", "shop");
        containerOfProject("plain1", null);
        assertThat(access.mayFollowContainer("viewer", host, "web1")).isTrue();
        assertThat(access.mayFollowContainer("viewer", host, "plain1")).isTrue();
    }

    @Test
    void failedContainerLookupFailsClosedForViewersWhenASystemProjectExists() {
        when(containers.inspect(eq(host), any(), eq("x"))).thenThrow(new IllegalStateException("docker down"));
        assertThat(access.mayFollowContainer("viewer", host, "x")).isFalse();
    }

    @Test
    void viewerWithOnlyADeploymentGrantMayNotFollowAProxyContainer() {
        when(permissions.hostFullyVisible(any(), any())).thenReturn(false);
        containerOfProject("proxy1", "shipir-proxy");
        assertThat(access.mayFollowContainer("viewer", host, "proxy1")).isFalse();
    }

    @Test
    void adminFollowsProxyContainersWithoutADockerLookup() {
        assertThat(access.mayFollowContainer("root", host, "proxy1")).isTrue();
        verify(containers, never()).inspect(any(), any(), any());
    }

    @Test
    void viewerMayNotFollowTheOutputOfAProxyRun() {
        var run = Optional.of(new ComposeOutputStreamService.RunTarget(host, proxy));
        assertThat(access.mayFollowRun("viewer", run)).isFalse();
        assertThat(access.mayFollowRun("root", run)).isTrue();
    }

    @Test
    void viewerStillFollowsOrdinaryRunsAndHostLevelRuns() {
        assertThat(access.mayFollowRun("viewer",
                Optional.of(new ComposeOutputStreamService.RunTarget(host, shop)))).isTrue();
        assertThat(access.mayFollowRun("viewer",
                Optional.of(new ComposeOutputStreamService.RunTarget(host, null)))).isTrue();
    }

    @Test
    void visibleProjectsOfADeploymentGrantViewerOmitsTheProxy() {
        when(permissions.hostFullyVisible(any(), any())).thenReturn(false);
        assertThat(access.visibleProjects("viewer", host)).contains(Set.of("shop"));
    }

    @Test
    void systemManagedProjectsListsTheProxy() {
        assertThat(access.systemManagedProjects(host)).containsExactly("shipir-proxy");
    }
}
