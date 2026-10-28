package dev.bethge.shipir.auth.oidc;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OidcRedirectTest {

    private static OidcProperties props(String publicBaseUrl) {
        return new OidcProperties(false, true, publicBaseUrl, Duration.ofSeconds(5), Duration.ofMinutes(10));
    }

    @Test
    void usesTheRequestOriginWithoutPublicBaseUrl() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("shipir.local");
        request.setServerPort(443);
        assertThat(OidcRedirect.uri(props(""), request)).isEqualTo("https://shipir.local/api/auth/oidc/callback");
    }

    @Test
    void publicBaseUrlWinsAndTrailingSlashIsDropped() {
        assertThat(OidcRedirect.uri(props("https://shipir.example.com/"), new MockHttpServletRequest()))
                .isEqualTo("https://shipir.example.com/api/auth/oidc/callback");
    }
}
