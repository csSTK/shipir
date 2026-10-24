package dev.bethge.shipir.auth.oidc;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "shipir.oidc.break-glass-local-admin=false")
class OidcBreakGlassOffTest extends OidcTestBase {

    @Test
    void adminCannotUseLocalLoginWhenBreakGlassIsOff() throws Exception {
        configure(true, false);
        var r = loginAttempt("admin", PASSWORD, anonymousCsrf());
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("LOCAL_LOGIN_DISABLED");
    }
}
