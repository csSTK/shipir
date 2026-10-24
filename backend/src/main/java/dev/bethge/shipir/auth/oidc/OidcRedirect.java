package dev.bethge.shipir.auth.oidc;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** The redirect URI registered with the provider; one place so the login flow and the settings page agree. */
final class OidcRedirect {

    static final String CALLBACK_PATH = "/api/auth/oidc/callback";

    private OidcRedirect() {
    }

    static String uri(OidcProperties properties, HttpServletRequest request) {
        String base = properties.publicBaseUrl().isBlank() ? ServletUriComponentsBuilder.fromContextPath(request)
                .toUriString() : properties.publicBaseUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + CALLBACK_PATH;
    }
}
