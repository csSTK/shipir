package dev.bethge.shipir.auth.oidc;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OidcLoginTest extends OidcTestBase {

    @Test
    void publicConfigSaysDisabledWithoutProvider() throws Exception {
        mvc.perform(get("/api/auth/oidc/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.localLoginEnabled").value(true));
    }

    @Test
    void publicConfigShowsProviderHostWhenConfigured() throws Exception {
        configure(true, true);
        mvc.perform(get("/api/auth/oidc/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.displayName").value("127.0.0.1"))
                .andExpect(jsonPath("$.localLoginEnabled").value(true));
    }

    @Test
    void authorizeIsRefusedWhenNotConfigured() throws Exception {
        MvcResult r = mvc.perform(get("/api/auth/oidc/authorize")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        assertThat(r.getResponse().getHeader("Location")).isEqualTo("/?oidc_error=OIDC_NOT_CONFIGURED");
    }

    @Test
    void authorizeRedirectsToProviderWithPkceStateAndNonce() throws Exception {
        configure(true, true);
        MvcResult r = mvc.perform(get("/api/auth/oidc/authorize")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        String location = r.getResponse().getHeader("Location");
        assertThat(location).startsWith(provider.issuer() + "/authorize?");
        Map<String, String> q = query(location);
        assertThat(q).containsEntry("response_type", "code")
                .containsEntry("client_id", StubOidcProvider.CLIENT_ID)
                .containsEntry("code_challenge_method", "S256");
        assertThat(q.get("scope")).contains("openid");
        assertThat(q.get("redirect_uri")).endsWith("/api/auth/oidc/callback");
        assertThat(q.get("state")).hasSizeGreaterThanOrEqualTo(32);
        assertThat(q.get("nonce")).hasSizeGreaterThanOrEqualTo(32);
        assertThat(q.get("code_challenge")).hasSizeGreaterThanOrEqualTo(43);
        assertThat(location).doesNotContain(StubOidcProvider.CLIENT_SECRET);
    }

    @Test
    void loginProvisionsUserWithMappedRoleAndStartsSession() throws Exception {
        configure(true, true);
        Flow f = signIn(claims("sub-1", "alice", "ops"));

        assertThat(f.callbackLocation()).isEqualTo("/");
        String me = meJson(f.session());
        assertThat(me).contains("\"username\":\"alice\"", "\"role\":\"OPERATOR\"", "\"setupRequired\":false");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT external_issuer, external_subject, password_changed_at FROM users WHERE username = 'alice'");
        assertThat(row.get("external_issuer")).isEqualTo(provider.issuer());
        assertThat(row.get("external_subject")).isEqualTo("sub-1");
        assertThat(row.get("password_changed_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'OIDC_LOGIN' "
                + "AND outcome = 'SUCCESS' AND username = 'alice'", Integer.class)).isEqualTo(1);
    }

    @Test
    void sessionIdIsRotatedAtLogin() throws Exception {
        configure(true, true);
        MvcResult authorize = mvc.perform(get("/api/auth/oidc/authorize")).andReturn();
        String before = authorize.getRequest().getSession(false).getId();
        Map<String, String> q = query(authorize.getResponse().getHeader("Location"));
        provider.grant("c-rot", q.get("code_challenge"), q.get("nonce"), claims("sub-rot", "rot", "ops"));
        MvcResult cb = mvc.perform(get("/api/auth/oidc/callback").param("code", "c-rot").param("state", q.get("state"))
                .session((MockHttpSession) authorize.getRequest().getSession(false))).andReturn();
        assertThat(cb.getRequest().getSession(false).getId()).isNotEqualTo(before);
    }

    @Test
    void highestMatchingRoleWins() throws Exception {
        configure(true, true);
        Flow f = signIn(claims("sub-2", "bob", "viewers", "admins", "ops"));
        assertThat(meJson(f.session())).contains("\"role\":\"ADMIN\"");
    }

    @Test
    void groupsClaimAsSingleStringIsAccepted() throws Exception {
        configure(true, true);
        Map<String, Object> c = claims("sub-3", "carol");
        c.put("groups", "viewers");
        Flow f = signIn(c);
        assertThat(meJson(f.session())).contains("\"role\":\"VIEWER\"");
    }

    @Test
    void noMatchingGroupIsDeniedAndCreatesNoUser() throws Exception {
        configure(true, true);
        Flow f = signIn(claims("sub-4", "dave", "unrelated"));

        assertThat(f.callbackLocation()).isEqualTo("/?oidc_error=ROLE_DENIED");
        assertThat(meJson(f.session())).contains("UNAUTHENTICATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = 'dave'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'OIDC_LOGIN' "
                + "AND outcome = 'FAILURE'", Integer.class)).isEqualTo(1);
    }

    @Test
    void unknownUserIsRefusedWhenAutoProvisionIsOff() throws Exception {
        configure(false, true);
        Flow f = signIn(claims("sub-5", "erin", "ops"));
        assertThat(f.callbackLocation()).isEqualTo("/?oidc_error=USER_NOT_PROVISIONED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = 'erin'", Integer.class)).isZero();
    }

    @Test
    void knownIdentityLogsInWithAutoProvisionOffAndRoleFollowsGroups() throws Exception {
        configure(true, true);
        signIn(claims("sub-6", "frank", "ops"));
        configure(false, true);

        Flow f = signIn(claims("sub-6", "frank", "viewers"));
        assertThat(f.callbackLocation()).isEqualTo("/");
        assertThat(meJson(f.session())).contains("\"role\":\"VIEWER\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE external_subject = 'sub-6'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void knownIdentityWithoutMatchingGroupIsDenied() throws Exception {
        configure(true, true);
        signIn(claims("sub-7", "gina", "ops"));
        Flow f = signIn(claims("sub-7", "gina", "nobody"));
        assertThat(f.callbackLocation()).isEqualTo("/?oidc_error=ROLE_DENIED");
    }

    @Test
    void disabledUserIsRefused() throws Exception {
        configure(true, true);
        signIn(claims("sub-8", "hank", "ops"));
        jdbc.update("UPDATE users SET disabled_at = now() WHERE username = 'hank'");

        Flow f = signIn(claims("sub-8", "hank", "ops"));
        assertThat(f.callbackLocation()).isEqualTo("/?oidc_error=USER_DISABLED");
        assertThat(meJson(f.session())).contains("UNAUTHENTICATED");
    }

    @Test
    void identityIsLinkedBySubjectNeverByUsernameOrEmail() throws Exception {
        configure(true, true);
        // the provider claims to be "operator" with that user's email, but it is a different subject
        Map<String, Object> c = claims("sub-9", "operator", "admins");
        c.put("email", "operator@example.com");
        Flow f = signIn(c);

        assertThat(f.callbackLocation()).isEqualTo("/");
        assertThat(meJson(f.session())).doesNotContain("\"username\":\"operator\"").contains("\"role\":\"ADMIN\"");
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE username = 'operator'", String.class))
                .isEqualTo("OPERATOR");
        assertThat(jdbc.queryForObject("SELECT external_subject FROM users WHERE username = 'operator'", String.class))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE external_subject = 'sub-9'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void ssoUserHasNoUsablePasswordAndIsNotTrappedInSetup() throws Exception {
        configure(true, true);
        signIn(claims("sub-10", "ivy", "ops"));
        MvcResult r = loginAttempt("ivy", "anything-at-all-123!ABC", anonymousCsrf());
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE username = 'ivy'", String.class))
                .startsWith("{argon2");
    }

    @Test
    void wrongStateIsRejectedAndSessionNotAuthenticated() throws Exception {
        configure(true, true);
        MvcResult authorize = mvc.perform(get("/api/auth/oidc/authorize")).andReturn();
        Map<String, String> q = query(authorize.getResponse().getHeader("Location"));
        provider.grant("c-bad-state", q.get("code_challenge"), q.get("nonce"), claims("sub-11", "jo", "ops"));
        MockHttpSession session = (MockHttpSession) authorize.getRequest().getSession(false);

        MvcResult cb = mvc.perform(get("/api/auth/oidc/callback").param("code", "c-bad-state")
                .param("state", "forged").session(session)).andReturn();
        assertThat(cb.getResponse().getHeader("Location")).isEqualTo("/?oidc_error=INVALID_STATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = 'jo'", Integer.class)).isZero();
    }

    @Test
    void callbackWithoutPriorAuthorizeIsRejected() throws Exception {
        configure(true, true);
        MvcResult cb = mvc.perform(get("/api/auth/oidc/callback").param("code", "x").param("state", "y")).andReturn();
        assertThat(cb.getResponse().getHeader("Location")).isEqualTo("/?oidc_error=INVALID_STATE");
    }

    @Test
    void stateIsSingleUse() throws Exception {
        configure(true, true);
        Flow f = signIn(claims("sub-12", "kim", "ops"));
        provider.grant("c-replay", f.authorizeQuery().get("code_challenge"), f.authorizeQuery().get("nonce"),
                claims("sub-12", "kim", "ops"));
        MvcResult replay = mvc.perform(get("/api/auth/oidc/callback").param("code", "c-replay")
                .param("state", f.authorizeQuery().get("state")).session(f.session())).andReturn();
        assertThat(replay.getResponse().getHeader("Location")).isEqualTo("/?oidc_error=INVALID_STATE");
    }

    @Test
    void wrongNonceInIdTokenIsRejected() throws Exception {
        configure(true, true);
        MvcResult authorize = mvc.perform(get("/api/auth/oidc/authorize")).andReturn();
        Map<String, String> q = query(authorize.getResponse().getHeader("Location"));
        provider.grant("c-nonce", q.get("code_challenge"), "some-other-nonce", claims("sub-13", "lee", "ops"));
        MvcResult cb = mvc.perform(get("/api/auth/oidc/callback").param("code", "c-nonce").param("state", q.get("state"))
                .session((MockHttpSession) authorize.getRequest().getSession(false))).andReturn();
        assertThat(cb.getResponse().getHeader("Location")).isEqualTo("/?oidc_error=INVALID_NONCE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = 'lee'", Integer.class)).isZero();
    }

    @Test
    void idTokenWithWrongAudienceOrIssuerIsRejected() throws Exception {
        configure(true, true);
        provider.signWithAudience("someone-else");
        assertThat(signIn(claims("sub-14", "max", "ops")).callbackLocation()).isEqualTo("/?oidc_error=ID_TOKEN_INVALID");
        provider.reset();
        provider.signWithIssuer("http://evil.example.com");
        assertThat(signIn(claims("sub-14", "max", "ops")).callbackLocation()).isEqualTo("/?oidc_error=ID_TOKEN_INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = 'max'", Integer.class)).isZero();
    }

    @Test
    void wrongPkceVerifierFailsTokenExchange() throws Exception {
        configure(true, true);
        MvcResult authorize = mvc.perform(get("/api/auth/oidc/authorize")).andReturn();
        Map<String, String> q = query(authorize.getResponse().getHeader("Location"));
        provider.grant("c-pkce", "challenge-for-another-verifier", q.get("nonce"), claims("sub-15", "ned", "ops"));
        MvcResult cb = mvc.perform(get("/api/auth/oidc/callback").param("code", "c-pkce").param("state", q.get("state"))
                .session((MockHttpSession) authorize.getRequest().getSession(false))).andReturn();
        assertThat(cb.getResponse().getHeader("Location")).isEqualTo("/?oidc_error=TOKEN_EXCHANGE_FAILED");
    }

    @Test
    void providerErrorIsReportedWithoutLeakingItsText() throws Exception {
        configure(true, true);
        MvcResult authorize = mvc.perform(get("/api/auth/oidc/authorize")).andReturn();
        Map<String, String> q = query(authorize.getResponse().getHeader("Location"));
        MvcResult cb = mvc.perform(get("/api/auth/oidc/callback").param("error", "access_denied")
                .param("state", q.get("state"))
                .param("error_description", "<script>x</script>")
                .session((MockHttpSession) authorize.getRequest().getSession(false))).andReturn();
        assertThat(cb.getResponse().getHeader("Location")).isEqualTo("/?oidc_error=OIDC_PROVIDER_ERROR");
    }

    @Test
    void unreachableProviderIsReported() throws Exception {
        configure(true, true);
        provider.breakDiscovery();
        MvcResult r = mvc.perform(get("/api/auth/oidc/authorize")).andReturn();
        assertThat(r.getResponse().getHeader("Location")).isEqualTo("/?oidc_error=PROVIDER_UNREACHABLE");
    }

    @Test
    void auditEntriesNeverContainTokensOrSecrets() throws Exception {
        configure(true, true);
        signIn(claims("sub-16", "oli", "ops"));
        List<String> rows = jdbc.queryForList("SELECT coalesce(details::text, '') || coalesce(target_id, '') "
                + "FROM audit_log", String.class);
        assertThat(String.join("|", rows)).doesNotContain(StubOidcProvider.CLIENT_SECRET).doesNotContain("eyJ");
    }

    private void assertIdTokenRejected(String username) throws Exception {
        assertThat(signIn(claims("sub-x-" + username, username, "ops")).callbackLocation())
                .isEqualTo("/?oidc_error=ID_TOKEN_INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, username))
                .isZero();
    }

    @Test
    void expiredIdTokenIsRejected() throws Exception {
        configure(true, true);
        provider.expiresIn(-600_000);
        assertIdTokenRejected("expired");
    }

    @Test
    void idTokenWithoutExpIsRejected() throws Exception {
        configure(true, true);
        provider.omitExp();
        assertIdTokenRejected("noexp");
    }

    @Test
    void idTokenWithoutIatIsRejected() throws Exception {
        configure(true, true);
        provider.omitIat();
        assertIdTokenRejected("noiat");
    }

    @Test
    void idTokenSignedWithUnknownKeyIsRejected() throws Exception {
        configure(true, true);
        provider.signWithForeignKey();
        assertIdTokenRejected("forged");
    }

    @Test
    void discoveryDocumentWithOtherIssuerIsRejected() throws Exception {
        configure(true, true);
        provider.advertiseIssuer("http://evil.example.com");
        MvcResult r = mvc.perform(get("/api/auth/oidc/authorize")).andReturn();
        assertThat(r.getResponse().getHeader("Location")).isEqualTo("/?oidc_error=PROVIDER_INVALID");
    }

    @Test
    void multipleAudiencesRequireMatchingAuthorizedParty() throws Exception {
        configure(true, true);
        provider.audiences(List.of(StubOidcProvider.CLIENT_ID, "other-api"));
        assertIdTokenRejected("noazp");

        provider.reset();
        provider.audiences(List.of(StubOidcProvider.CLIENT_ID, "other-api"));
        Map<String, Object> c = claims("sub-azp", "azpok", "ops");
        c.put("azp", StubOidcProvider.CLIENT_ID);
        assertThat(signIn(c).callbackLocation()).isEqualTo("/");
    }
}
