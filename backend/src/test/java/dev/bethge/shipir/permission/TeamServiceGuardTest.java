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
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The public {@link TeamService#get} guards itself: ADMIN only, whoever calls it (e.g. the AI tool). */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TeamServiceGuardTest {

    @Autowired
    private TeamService teams;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signInAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "u", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void nonAdminCallersAreDenied() {
        for (String role : List.of("OPERATOR", "VIEWER")) {
            signInAs(role);
            assertThatThrownBy(() -> teams.get(UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void unauthenticatedCallersAreDenied() {
        assertThatThrownBy(() -> teams.get(UUID.randomUUID()))
                .isInstanceOfAny(AccessDeniedException.class, AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void adminPassesTheGuardAndReachesTheService() {
        signInAs("ADMIN");
        assertThatThrownBy(() -> teams.get(UUID.randomUUID())).isInstanceOf(PermissionException.class);
    }
}
