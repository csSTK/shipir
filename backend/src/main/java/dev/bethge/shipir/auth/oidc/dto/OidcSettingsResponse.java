package dev.bethge.shipir.auth.oidc.dto;

import dev.bethge.shipir.auth.Role;

import java.util.List;
import java.util.Map;

/** The client secret is never part of it, only whether one is stored and a masked hint. */
public record OidcSettingsResponse(String issuerUrl, String clientId, boolean clientSecretConfigured,
                                   String clientSecretHint, String scopes, String groupsClaim,
                                   Map<Role, List<String>> roleMapping, boolean autoProvision,
                                   boolean localLoginEnabled, boolean enabled) {
}
