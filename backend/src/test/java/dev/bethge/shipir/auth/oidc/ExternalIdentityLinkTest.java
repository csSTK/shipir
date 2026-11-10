package dev.bethge.shipir.auth.oidc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADMIN pre-link of a local user to an OIDC identity: validation, uniqueness, audit, and the sign-in it enables. */
class ExternalIdentityLinkTest extends OidcTestBase {

    @AfterEach
    void unlinkSeededUsers() {
        // resetState() of the next test deletes every user that still has an identity: keep the seeded ones local
        jdbc.update("UPDATE users SET external_issuer = NULL, external_subject = NULL "
                + "WHERE username IN ('admin', 'operator', 'viewer')");
    }

    private UUID id(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", UUID.class, username);
    }

    private ResultActions link(Login who, String username, String issuer, String subject) throws Exception {
        return mvc.perform(who.write(put("/api/users/" + id(username) + "/external-identity"))
                .contentType(APPLICATION_JSON)
                .content("{\"issuer\":%s,\"subject\":%s}".formatted(quote(issuer), quote(subject))));
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\u0000", "\\u0000") + "\"";
    }

    private long audit(String action, String outcome) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND outcome = ?", Long.class,
                action, outcome);
    }

    @Test
    void prelinkedUserSignsInWithAutoProvisionOff() throws Exception {
        configure(false, true);
        Login admin = login("admin");
        link(admin, "viewer", provider.issuer(), "sub-pre").andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("viewer"))
                .andExpect(jsonPath("$.externalIdentity").value(true));

        Flow f = signIn(claims("sub-pre", "someone-else", "viewers"));

        assertThat(f.callbackLocation()).isEqualTo("/");
        assertThat(meJson(f.session())).contains("\"username\":\"viewer\"");
        assertThat(audit("USER_IDENTITY_LINK", "SUCCESS")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT details::text FROM audit_log WHERE action = 'USER_IDENTITY_LINK'",
                String.class)).contains("sub-pre");
    }

    @Test
    void unlinkedIdentityIsStillRefusedAndUnlinkRemovesAccess() throws Exception {
        configure(false, true);
        Login admin = login("admin");
        link(admin, "viewer", provider.issuer(), "sub-un").andExpect(status().isOk());
        mvc.perform(admin.write(delete("/api/users/" + id("viewer") + "/external-identity")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.externalIdentity").value(false));

        assertThat(signIn(claims("sub-un", "viewer", "viewers")).callbackLocation())
                .isEqualTo("/?oidc_error=USER_NOT_PROVISIONED");
        assertThat(audit("USER_IDENTITY_UNLINK", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void unlinkEndsTheUsersSessions() throws Exception {
        configure(false, true);
        Login admin = login("admin");
        link(admin, "operator", provider.issuer(), "sub-sess").andExpect(status().isOk());
        Login op = login("operator");
        mvc.perform(admin.write(delete("/api/users/" + id("operator") + "/external-identity")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users").session(op.session())).andExpect(status().isUnauthorized());
    }

    @Test
    void identityBelongsToOneUserOnly() throws Exception {
        Login admin = login("admin");
        link(admin, "viewer", "https://idp.example.com", "sub-x").andExpect(status().isOk());
        link(admin, "operator", "https://idp.example.com", "sub-x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDENTITY_TAKEN"));
        // same subject at another issuer is a different identity
        link(admin, "operator", "https://other.example.com", "sub-x").andExpect(status().isOk());
        assertThat(audit("USER_IDENTITY_LINK", "FAILURE")).isEqualTo(1);
    }

    @Test
    void userHasOneIdentityOnly() throws Exception {
        Login admin = login("admin");
        link(admin, "viewer", "https://idp.example.com", "sub-1").andExpect(status().isOk());
        link(admin, "viewer", "https://idp.example.com", "sub-2").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDENTITY_ALREADY_LINKED"));
        assertThat(jdbc.queryForObject("SELECT external_subject FROM users WHERE username = 'viewer'", String.class))
                .isEqualTo("sub-1");
    }

    @Test
    void malformedIssuerAndSubjectAreRejected() throws Exception {
        Login admin = login("admin");
        for (String issuer : new String[]{"https://user:pw@idp.example.com",
                "https://idp.example.com?x=1", "https://idp.example.com#f", "ftp://idp.example.com", "javascript:alert(1)",
                "not a url", "https://", "file:///etc/passwd", "https://idp.example.com/" + "a".repeat(2100), "  "}) {
            link(admin, "viewer", issuer, "sub").andExpect(status().isBadRequest());
        }
        link(admin, "viewer", null, "sub").andExpect(status().isBadRequest());
        for (String subject : new String[]{"", "   ", " lead", "trail ", "a\nb", "a\u0000b", "x".repeat(256)}) {
            link(admin, "viewer", "https://idp.example.com", subject).andExpect(status().isBadRequest());
        }
        link(admin, "viewer", "https://idp.example.com", null).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT external_issuer FROM users WHERE username = 'viewer'", String.class))
                .isNull();
    }

    @Test
    void unknownUserIs404() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put("/api/users/" + UUID.randomUUID() + "/external-identity"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"issuer\":\"https://idp.example.com\",\"subject\":\"s\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(admin.write(delete("/api/users/" + UUID.randomUUID() + "/external-identity")))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCannotLinkOrUnlinkOwnIdentity() throws Exception {
        Login admin = login("admin");
        link(admin, "admin", "https://idp.example.com", "sub-admin").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_LINK"));
        mvc.perform(admin.write(delete("/api/users/" + id("admin") + "/external-identity")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SELF_UNLINK"));
        assertThat(jdbc.queryForObject("SELECT external_issuer FROM users WHERE username = 'admin'", String.class))
                .isNull();
    }

    @Test
    void nonAdminsAndAnonymousCannotLinkOrUnlink() throws Exception {
        Login op = login("operator");
        Login viewer = login("viewer");
        for (Login who : new Login[]{op, viewer}) {
            link(who, "viewer", "https://idp.example.com", "s").andExpect(status().isForbidden());
            mvc.perform(who.write(delete("/api/users/" + id("viewer") + "/external-identity")))
                    .andExpect(status().isForbidden());
        }
        jakarta.servlet.http.Cookie anon = anonymousCsrf();
        mvc.perform(put("/api/users/" + id("viewer") + "/external-identity").cookie(anon)
                .header("X-XSRF-TOKEN", anon.getValue()).contentType(APPLICATION_JSON)
                .content("{\"issuer\":\"https://idp.example.com\",\"subject\":\"s\"}")).andExpect(status().isUnauthorized());
        // CSRF is required for the ADMIN too
        Login admin = login("admin");
        mvc.perform(put("/api/users/" + id("viewer") + "/external-identity").session(admin.session())
                .contentType(APPLICATION_JSON)
                .content("{\"issuer\":\"https://idp.example.com\",\"subject\":\"s\"}")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT external_issuer FROM users WHERE username = 'viewer'", String.class))
                .isNull();
    }

    @Test
    void subjectIsMatchedExactly() throws Exception {
        configure(false, true);
        Login admin = login("admin");
        link(admin, "viewer", provider.issuer(), "Sub-Case").andExpect(status().isOk());
        assertThat(signIn(claims("sub-case", "viewer", "viewers")).callbackLocation())
                .isEqualTo("/?oidc_error=USER_NOT_PROVISIONED");
    }

    @Test
    void trailingSlashAndHostCaseVariantLinksAndStoresConfiguredIssuer() throws Exception {
        configure(false, true);
        Login admin = login("admin");
        String variant = "HTTP" + provider.issuer().substring(4) + "/";
        link(admin, "viewer", variant, "sub-slash").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT external_issuer FROM users WHERE username = 'viewer'", String.class))
                .isEqualTo(provider.issuer());
        assertThat(signIn(claims("sub-slash", "x", "viewers")).callbackLocation()).isEqualTo("/");
    }

    @Test
    void differentIssuerIsRejectedWhenOidcIsConfigured() throws Exception {
        configure(false, true);
        Login admin = login("admin");
        link(admin, "viewer", "https://other.example.com", "sub-x").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ISSUER_MISMATCH"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(provider.issuer())));
        assertThat(jdbc.queryForObject("SELECT external_issuer FROM users WHERE username = 'viewer'", String.class))
                .isNull();
    }
}
