package dev.bethge.shipir.permission;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.security.SystemScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The system bypass is explicit: only {@link SystemScope} lifts permission checks on a thread without authentication. */
class CurrentAccessTest {

    private final PermissionService permissions = mock(PermissionService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CurrentAccess access = new CurrentAccess(permissions, users);
    private final UUID host = UUID.randomUUID();
    private final UUID deployment = UUID.randomUUID();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthenticationAndNoScopeIsDeniedEverything() {
        when(permissions.visibleHostIds(any())).thenReturn(Set.of());
        when(permissions.visibleDeploymentIds(any(), any())).thenReturn(Set.of());

        assertThat(access.canView(host)).isFalse();
        assertThat(access.canOperate(host)).isFalse();
        assertThat(access.canView(host, deployment)).isFalse();
        assertThat(access.canOperate(host, deployment)).isFalse();
        assertThat(access.hostFullyVisible(host)).isFalse();
        assertThat(access.visibleHostIds()).contains(Set.of());
        assertThat(access.visibleDeploymentIds(host)).contains(Set.of());
    }

    @Test
    void systemScopeWithoutAuthenticationAllowsEverythingAndFiltersNothing() {
        SystemScope.run(() -> {
            assertThat(access.canView(host)).isTrue();
            assertThat(access.canOperate(host)).isTrue();
            assertThat(access.canView(host, deployment)).isTrue();
            assertThat(access.canOperate(host, deployment)).isTrue();
            assertThat(access.hostFullyVisible(host)).isTrue();
            assertThat(access.visibleHostIds()).isEmpty();
            assertThat(access.visibleDeploymentIds(host)).isEmpty();
        });
        assertThat(SystemScope.active()).isFalse();
        assertThat(access.canView(host)).isFalse();
    }

    @Test
    void authenticatedUserKeepsHisOwnRightsInsideTheSystemScope() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(user.getRole()).thenReturn(Role.VIEWER);
        when(users.findByUsername("viewer")).thenReturn(Optional.of(user));
        when(permissions.canView(any(), any())).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("viewer", "x", List.of()));

        SystemScope.run(() -> assertThat(access.canView(host)).isFalse());
    }
}
