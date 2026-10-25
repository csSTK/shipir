package dev.bethge.shipir.auth.token;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

/** The authentication of a request carrying a valid API token: the token's user with that user's current role. */
public final class ApiTokenAuthentication extends AbstractAuthenticationToken {

    private final String username;
    private final UUID tokenId;

    ApiTokenAuthentication(String username, String role, UUID tokenId) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        this.username = username;
        this.tokenId = tokenId;
        setAuthenticated(true);
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
