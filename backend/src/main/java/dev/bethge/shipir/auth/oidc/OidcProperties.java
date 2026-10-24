package dev.bethge.shipir.auth.oidc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Bound from {@code shipir.oidc.*}.
 * {@code allowInsecureHttp} exists only so tests can reach a local stub; https is the only scheme otherwise.
 * {@code breakGlassLocalAdmin}: while local login is disabled in the settings, ADMIN users may still sign in with
 * their password, so a broken provider can never lock everybody out.
 * {@code publicBaseUrl}: externally visible origin (e.g. {@code https://shipir.example.com}) used for the redirect
 * URI; empty = the origin of the request as the backend sees it (set it behind a reverse proxy).
 */
@ConfigurationProperties("shipir.oidc")
record OidcProperties(@DefaultValue("false") boolean allowInsecureHttp,
                      @DefaultValue("true") boolean breakGlassLocalAdmin,
                      @DefaultValue("") String publicBaseUrl,
                      @DefaultValue("5s") Duration timeout) {
}
