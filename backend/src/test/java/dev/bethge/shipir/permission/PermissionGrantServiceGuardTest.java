package dev.bethge.shipir.permission;

import dev.bethge.shipir.PostgresTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The public read methods and the mutators of {@link PermissionGrantService} guard themselves: ADMIN only, whoever calls them. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PermissionGrantServiceGuardTest {

    @Autowired
    private PermissionGrantService grants;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signInAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "u", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static final List<Consumer<PermissionGrantService>> READS = List.of(
            s -> s.get(UUID.randomUUID()),
            s -> s.getForTeam(UUID.randomUUID()),
            s -> s.teamsOf(UUID.randomUUID()),
            s -> s.forHost(UUID.randomUUID()),
            s -> s.forDeployment(UUID.randomUUID()));

    private static final List<Consumer<PermissionGrantService>> WRITES = List.of(
            s -> s.replace(UUID.randomUUID(), List.of()),
            s -> s.replaceForTeam(UUID.randomUUID(), List.of()));

    private static List<Consumer<PermissionGrantService>> all() {
        return java.util.stream.Stream.concat(READS.stream(), WRITES.stream()).toList();
    }

    @Test
    void nonAdminCallersAreDeniedOnEveryReadAndWriteMethod() {
        for (String role : List.of("OPERATOR", "VIEWER")) {
            signInAs(role);
            for (Consumer<PermissionGrantService> read : all()) {
                assertThatThrownBy(() -> read.accept(grants)).isInstanceOf(AccessDeniedException.class);
            }
        }
    }

    @Test
    void unauthenticatedCallersAreDenied() {
        for (Consumer<PermissionGrantService> read : all()) {
            assertThatThrownBy(() -> read.accept(grants))
                    .isInstanceOfAny(AccessDeniedException.class, AuthenticationCredentialsNotFoundException.class);
        }
    }

    @Test
    void adminPassesTheGuardAndReachesTheService() {
        signInAs("ADMIN");
        for (Consumer<PermissionGrantService> read : all()) {
            assertThatThrownBy(() -> read.accept(grants)).isInstanceOf(PermissionException.class);
        }
        assertThat(true).isTrue();
    }
}
