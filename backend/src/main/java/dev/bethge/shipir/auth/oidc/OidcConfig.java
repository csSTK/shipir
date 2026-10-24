package dev.bethge.shipir.auth.oidc;

import dev.bethge.shipir.auth.Role;

import java.util.List;
import java.util.Map;

/** Decrypted, ready-to-use configuration for one sign-in. {@code toString} hides the secret. */
record OidcConfig(String issuer, String clientId, String clientSecret, String scopes, String groupsClaim,
                  Map<Role, List<String>> roleMapping, boolean autoProvision) {

    @Override
    public String toString() {
        return "OidcConfig[issuer=" + issuer + ", clientId=" + clientId + ", clientSecret=<hidden>]";
    }
}
