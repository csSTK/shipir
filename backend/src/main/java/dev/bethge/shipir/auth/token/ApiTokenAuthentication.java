package dev.bethge.shipir.auth.token;

import dev.bethge.shipir.auth.Role;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

/**
 * The authentication of a request carrying a valid API token: the token's user with that user's current role, capped
 * at {@link #MAX_ROLE}. Tokens are unscoped, so they never carry ADMIN power (user management, backup, settings, exec,
 * host-level compose features): that stays with the interactive, 2FA-protected session.
 */
public final class ApiTokenAuthentication extends AbstractAuthenticationToken {

    public static final Role MAX_ROLE = Role.OPERATOR;

    private final String username;
    private final UUID tokenId;

    ApiTokenAuthentication(String username, Role role, UUID tokenId) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + cap(role).name())));
        this.username = username;
        this.tokenId = tokenId;
        setAuthenticated(true);
    }

    private static Role cap(Role role) {
        return role == Role.ADMIN ? MAX_ROLE : role;
    }

    /**
     * The role a service must use for the caller: the user's stored role, except for a token request where it is
     * capped. Every place that reads the role from the database instead of the authorities must go through this.
     */
    public static Role effectiveRole(Authentication authentication, Role storedRole) {
        return authentication instanceof ApiTokenAuthentication ? cap(storedRole) : storedRole;
    }

    public UUID tokenId() {
        return tokenId;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return username;
    }
}
