package dev.bethge.shipir.auth.oidc.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * PUT body. A blank {@code issuerUrl} switches single sign-on off. {@code clientSecret}: null keeps the stored one,
 * an empty string clears it. {@code roleMapping} is {@code {"ADMIN":["group"],...}}, validated by the service.
 */
public record OidcSettingsRequest(@Size(max = 2048) String issuerUrl,
                                  @Size(max = 255) String clientId,
                                  @Size(max = 1024) String clientSecret,
                                  @Size(max = 512) String scopes,
                                  @Size(max = 255) String groupsClaim,
                                  JsonNode roleMapping,
                                  @NotNull Boolean autoProvision,
                                  @NotNull Boolean localLoginEnabled) {

    /** The secret is left out. */
    @Override
    public String toString() {
        return "OidcSettingsRequest[issuerUrl=" + issuerUrl + ", clientId=" + clientId + ", clientSecret=<hidden>]";
    }
}
