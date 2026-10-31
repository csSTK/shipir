package dev.bethge.shipir.streaming;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.client.ContainerService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** An exec console into the system-managed proxy's containers is ADMIN-only, even with operator-enabled and OPERATE. */
class StreamAccessSystemManagedExecTest {

    private final UUID host = UUID.randomUUID();
    private final UUID proxy = UUID.randomUUID();
    private final UUID shop = UUID.randomUUID();
    private StreamAccess access;

    @BeforeEach
    void setUp() {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.canOperate(any(), any(UUID.class))).thenReturn(true);
        when(permissions.canOperate(any(), any(UUID.class), any(UUID.class))).thenReturn(true);
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("op")).thenReturn(Optional.of(new User("op", "x", Role.OPERATOR)));
        when(users.findByUsername("root")).thenReturn(Optional.of(new User("root", "x", Role.ADMIN)));
        ManagedDeploymentLookup managed = new ManagedDeploymentLookup() {
            @Override public Map<String, UUID> managedDeploymentIds(UUID hostId) {
                return Map.of("shipir-proxy", proxy, "shop", shop);
            }
            @Override public Set<String> systemManagedNames(UUID hostId) {
                return Set.of("shipir-proxy");
            }
        };
        access = new StreamAccess(permissions, users, managed, mock(ContainerService.class),
                mock(DockerHostRepository.class), mock(HostEndpointResolver.class));
    }

    @Test
    void operatorMayNotExecIntoTheProxyEvenWhenEnabledAndOperable() {
        assertThat(access.mayExec("op", host, proxy, true)).isFalse();
    }

    @Test
    void operatorStillExecsIntoOrdinaryContainersAndUnmanagedOnes() {
        assertThat(access.mayExec("op", host, shop, true)).isTrue();
        assertThat(access.mayExec("op", host, null, true)).isTrue();
    }

    @Test
    void adminExecsIntoTheProxy() {
        assertThat(access.mayExec("root", host, proxy, false)).isTrue();
    }

    @Test
    void execScopeOfAnOperatorWithDeploymentGrantsOmitsTheProxyProject() {
        PermissionService permissions = mock(PermissionService.class);
        // host-level OPERATE is absent, only per-deployment grants
        when(permissions.canView(any(), any(UUID.class))).thenReturn(true);
        when(permissions.canOperate(any(), any(UUID.class))).thenReturn(false);
        when(permissions.canOperate(any(), any(UUID.class), any(UUID.class))).thenReturn(true);
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("op")).thenReturn(Optional.of(new User("op", "x", Role.OPERATOR)));
        ManagedDeploymentLookup managed = new ManagedDeploymentLookup() {
            @Override public Map<String, UUID> managedDeploymentIds(UUID hostId) {
                return Map.of("shipir-proxy", proxy, "shop", shop);
            }
            @Override public Set<String> systemManagedNames(UUID hostId) {
                return Set.of("shipir-proxy");
            }
        };
        StreamAccess a = new StreamAccess(permissions, users, managed, mock(ContainerService.class),
                mock(DockerHostRepository.class), mock(HostEndpointResolver.class));
        assertThat(a.execScope("op", host, true).operableProjects()).containsExactly("shop");
    }
}
