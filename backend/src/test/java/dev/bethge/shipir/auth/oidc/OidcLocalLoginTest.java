package dev.bethge.shipir.auth.oidc;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

class OidcLocalLoginTest extends OidcTestBase {

    @Test
    void localLoginStaysAvailableWhenEnabled() throws Exception {
        configure(true, true);
        assertThat(loginAttempt("operator", PASSWORD, anonymousCsrf()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void localLoginIsRefusedForNonAdminsWhenDisabled() throws Exception {
        configure(true, false);
        MvcResult r = loginAttempt("operator", PASSWORD, anonymousCsrf());
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("LOCAL_LOGIN_DISABLED");
        assertThat(r.getRequest().getSession(false)).isNull();
    }

    @Test
    void wrongPasswordStillLooksLikeAnyOtherFailureWhenDisabled() throws Exception {
        configure(true, false);
        assertThat(loginAttempt("operator", "wrong-password-123!ABC", anonymousCsrf()).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(loginAttempt("nobody", "wrong-password-123!ABC", anonymousCsrf()).getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void breakGlassLetsAdminInWhenLocalLoginIsDisabled() throws Exception {
        configure(true, false);
        assertThat(loginAttempt("admin", PASSWORD, anonymousCsrf()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void publicConfigReportsDisabledLocalLogin() throws Exception {
        configure(true, false);
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/auth/oidc/config")).andReturn().getResponse().getContentAsString())
                .contains("\"localLoginEnabled\":false");
    }
}
