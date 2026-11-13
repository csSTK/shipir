package dev.bethge.shipir.auth.oidc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Step-up for SSO users: IdP re-authentication (prompt=login, max_age) and the {@code auth_time} freshness check. */
class OidcStepUpTest extends OidcTestBase {

    private static final String TOKENS = "/api/account/tokens";

    @BeforeEach
    void configured() throws Exception {
        configure(true, true);
    }

    private static Map<String, Object> withAuthTime(Map<String, Object> claims, Instant authTime) {
        claims.put("auth_time", authTime.getEpochSecond());
        return claims;
    }

    private Login ssoLogin(Flow f) {
        MockHttpSession session = (MockHttpSession) f.callback().getRequest().getSession(false);
        return new Login(session, f.callback().getResponse().getCookie("XSRF-TOKEN"));
    }

    private MvcResult createToken(Login l) throws Exception {
        return mvc.perform(l.write(post(TOKENS)).contentType(APPLICATION_JSON).content("{\"name\":\"ci\"}")).andReturn();
    }

    private Flow signInWithAuthTime(String sub, Instant authTime) throws Exception {
        return signIn(withAuthTime(claims(sub, "sso-" + sub, "admins"), authTime));
    }

    /** Starts re-authentication and lets the stub return {@code claims}; returns the callback result. */
    private MvcResult reauth(Login l, String returnPath, Map<String, Object> claims) throws Exception {
        // own client address per flow: many flows in one test must not trip the per-address authorize throttle
        String address = "10.88." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250));
        var request = get("/api/auth/oidc/reauthorize").session(l.session()).with(req -> {
            req.setRemoteAddr(address);
            return req;
        });
        if (returnPath != null) {
            request.param("return", returnPath);
        }
        MvcResult start = mvc.perform(request).andReturn();
        assertThat(start.getResponse().getStatus()).isEqualTo(302);
        Map<String, String> q = query(start.getResponse().getHeader(HttpHeaders.LOCATION));
        assertThat(q).containsEntry("prompt", "login").containsEntry("max_age", "300");
        String code = "code-" + UUID.randomUUID();
        provider.grant(code, q.get("code_challenge"), q.get("nonce"), claims);
        return mvc.perform(get("/api/auth/oidc/callback").param("code", code).param("state", q.get("state"))
                .session(l.session())).andReturn();
    }

    @Test
    void freshAuthTimeAtSignInAllowsTheAction() throws Exception {
        Login l = ssoLogin(signInWithAuthTime("s1", Instant.now()));
        mvc.perform(get("/api/auth/step-up").session(l.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("SSO"))
                .andExpect(jsonPath("$.fresh").value(true))
                .andExpect(jsonPath("$.windowSeconds").value(300));
        assertThat(createToken(l).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void staleOrMissingAuthTimeIsRefusedWithTheSsoCode() throws Exception {
        Login stale = ssoLogin(signInWithAuthTime("s2", Instant.now().minusSeconds(600)));
        MvcResult r = createToken(stale);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("STEP_UP_SSO_REQUIRED");

        Login none = ssoLogin(signIn(claims("s3", "sso-s3", "admins")));
        assertThat(createToken(none).getResponse().getContentAsString()).contains("STEP_UP_SSO_REQUIRED");
        mvc.perform(get("/api/auth/step-up").session(none.session())).andExpect(jsonPath("$.fresh").value(false));
    }

    @Test
    void passwordsAreNotAcceptedFromAnSsoUser() throws Exception {
        Login l = ssoLogin(signInWithAuthTime("s4", Instant.now().minusSeconds(600)));
        MvcResult r = mvc.perform(l.write(post(TOKENS)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"ci\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("STEP_UP_SSO_REQUIRED");
    }

    @Test
    void reauthenticationMakesAStaleSessionFresh() throws Exception {
        Login l = ssoLogin(signInWithAuthTime("s5", Instant.now().minusSeconds(600)));
        MvcResult cb = reauth(l, "/settings/backup", withAuthTime(claims("s5", "sso-s5", "admins"), Instant.now()));
        assertThat(cb.getResponse().getStatus()).isEqualTo(302);
        assertThat(cb.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/settings/backup");
        assertThat(createToken(l).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void reauthenticationWithAStaleAuthTimeStaysRefused() throws Exception {
        Login l = ssoLogin(signInWithAuthTime("s6", Instant.now().minusSeconds(600)));
        MvcResult cb = reauth(l, null, withAuthTime(claims("s6", "sso-s6", "admins"), Instant.now().minusSeconds(900)));
        assertThat(cb.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/?oidc_error=STEP_UP_NOT_FRESH");
        assertThat(createToken(l).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void reauthenticationAsAnotherIdentityIsRefused() throws Exception {
        Login l = ssoLogin(signInWithAuthTime("s7", Instant.now().minusSeconds(600)));
        MvcResult cb = reauth(l, null, withAuthTime(claims("someone-else", "sso-x", "admins"), Instant.now()));
        assertThat(cb.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/?oidc_error=IDENTITY_MISMATCH");
        assertThat(createToken(l).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void reauthenticationDoesNotSignInAnotherUser() throws Exception {
        Login l = ssoLogin(signInWithAuthTime("s8", Instant.now().minusSeconds(600)));
        reauth(l, null, withAuthTime(claims("someone-else", "sso-y", "admins"), Instant.now()));
        assertThat(meJson(l.session())).contains("sso-s8");
    }

    @Test
    void theReturnPathMustBeALocalPath() throws Exception {
        for (String bad : new String[]{"//evil.example", "https://evil.example", "/\\evil", "relative",
                "/?x=1\r\nSet-Cookie:a=b", "/a\nb", "/?next=//evil.example/\\x", "/?a=b\\c", "/#\\evil",
                "/a?b c", "/a?b#c#d", "/a?b?c", "/a b", "/?a=<script>", "/\t/evil", "/?a=%", "/a%zz", "/a?#b#",
                "/\u2215evil", "/?\u0000", "javascript:alert(1)", "/a;jsessionid=1", "/?a=b@evil.example/", "/?a=b,c"}) {
            Login l = ssoLogin(signInWithAuthTime("s9", Instant.now().minusSeconds(600)));
            MvcResult cb = reauth(l, bad, withAuthTime(claims("s9", "sso-s9", "admins"), Instant.now()));
            assertThat(cb.getResponse().getHeader(HttpHeaders.LOCATION)).as(bad).isEqualTo("/");
        }
    }

    @Test
    void theReturnPathMayKeepQueryAndHashAndStaysSameOrigin() throws Exception {
        for (String good : new String[]{"/deployments/abc?tab=logs&filter=a%20b", "/containers#row-3",
                "/deployments/abc?tab=logs#env", "/?q=x", "/a/b.c~d_e-f"}) {
            Login l = ssoLogin(signInWithAuthTime("s10", Instant.now().minusSeconds(600)));
            MvcResult cb = reauth(l, good, withAuthTime(claims("s10", "sso-s10", "admins"), Instant.now()));
            assertThat(cb.getResponse().getHeader(HttpHeaders.LOCATION)).as(good).isEqualTo(good);
        }
    }

    @Test
    void anOverlongReturnPathIsDroppedWithoutAnError() throws Exception {
        Login l = ssoLogin(signInWithAuthTime("s11", Instant.now().minusSeconds(600)));
        MvcResult cb = reauth(l, "/" + "a".repeat(10_000), withAuthTime(claims("s11", "sso-s11", "admins"), Instant.now()));
        assertThat(cb.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/");
        Login l2 = ssoLogin(signInWithAuthTime("s12", Instant.now().minusSeconds(600)));
        String atCap = "/?q=" + "a%20".repeat(510) + "abcd";
        assertThat(atCap).hasSize(2048);
        MvcResult ok = reauth(l2, atCap, withAuthTime(claims("s12", "sso-s12", "admins"), Instant.now()));
        assertThat(ok.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(atCap);
    }

    @Test
    void aLaterSignInOnTheSameSessionDoesNotInheritTheEarlierUsersFreshness() throws Exception {
        Flow a = signInWithAuthTime("sa", Instant.now());
        MockHttpSession session = (MockHttpSession) a.callback().getRequest().getSession(false);
        // user B signs in on the very same session, and the provider sends no auth_time
        MvcResult authorize = mvc.perform(get("/api/auth/oidc/authorize").session(session)
                .with(req -> {
                    req.setRemoteAddr("10.77.0.1");
                    return req;
                })).andReturn();
        Map<String, String> q = query(authorize.getResponse().getHeader(HttpHeaders.LOCATION));
        String code = "code-" + UUID.randomUUID();
        provider.grant(code, q.get("code_challenge"), q.get("nonce"), claims("sb", "sso-sb", "admins"));
        MvcResult cb = mvc.perform(get("/api/auth/oidc/callback").param("code", code).param("state", q.get("state"))
                .session(session)).andReturn();
        assertThat(cb.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/");
        MockHttpSession after = (MockHttpSession) cb.getRequest().getSession(false);
        Login b = new Login(after, cb.getResponse().getCookie("XSRF-TOKEN"));
        assertThat(meJson(after)).contains("sso-sb");
        assertThat(createToken(b).getResponse().getContentAsString()).contains("STEP_UP_SSO_REQUIRED");
    }

    @Test
    void stepUpStatusIsNeverCached() throws Exception {
        Login l = ssoLogin(signInWithAuthTime("sc", Instant.now()));
        mvc.perform(get("/api/auth/step-up").session(l.session()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void reauthorizeNeedsASignedInSession() throws Exception {
        mvc.perform(get("/api/auth/oidc/reauthorize")).andExpect(status().isUnauthorized());
    }
}
