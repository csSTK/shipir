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

    @Test
    void refusedLocalLoginDoesNotClearTheThrottleCounter() throws Exception {
        configure(true, false);
        String wrong = "wrong-password-123!ABC";
        for (int i = 0; i < 4; i++) {
            assertThat(loginAttempt("viewer", wrong, anonymousCsrf()).getResponse().getStatus()).isEqualTo(401);
        }
        // Right password, but local login is off for this role: refused, and not a success for the throttle.
        MvcResult refused = loginAttempt("viewer", PASSWORD, anonymousCsrf());
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains("LOCAL_LOGIN_DISABLED");
        // The fifth failure still locks the account: the four earlier ones were not forgotten.
        assertThat(loginAttempt("viewer", wrong, anonymousCsrf()).getResponse().getStatus()).isEqualTo(401);
        assertThat(loginAttempt("viewer", PASSWORD, anonymousCsrf()).getResponse().getStatus()).isEqualTo(429);
    }
}
