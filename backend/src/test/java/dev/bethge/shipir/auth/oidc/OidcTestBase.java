package dev.bethge.shipir.auth.oidc;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real Postgres, stub OIDC provider, one local user per role, encryption key configured, ADMIN has an environment. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = "shipir.oidc.allow-insecure-http=true")
abstract class OidcTestBase {

    static final String PASSWORD = "A-test-Passw0rd-for-oidc-users!";
    static final String ROLE_MAPPING = "{\"ADMIN\":[\"admins\"],\"OPERATOR\":[\"ops\"],\"VIEWER\":[\"viewers\"]}";

    static StubOidcProvider provider;

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    OidcDiscoveryCache discoveryCacheForReset;

    @BeforeAll
    static void startProvider() throws Exception {
        provider = new StubOidcProvider();
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @BeforeEach
    void resetState() {
        provider.reset();
        discoveryCacheForReset.invalidate();
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM users WHERE external_issuer IS NOT NULL");
        jdbc.update("UPDATE oidc_settings SET issuer_url = NULL, client_id = NULL, client_secret_ciphertext = NULL, "
                + "client_secret_hint = NULL, scopes = 'openid email profile', groups_claim = NULL, "
                + "role_mapping = '{}'::jsonb, auto_provision = FALSE, local_login_enabled = TRUE");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            u.enable();
            users.save(u);
        }
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'OIDC host', 'LOCAL', id FROM users WHERE username = 'admin'");
    }

    record Login(MockHttpSession session, Cookie csrf) {

        MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
            return b.session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    Login login(String username) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = loginAttempt(username, PASSWORD, anon);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new Login((MockHttpSession) r.getRequest().getSession(false), anon);
    }

    MvcResult loginAttempt(String username, String password, Cookie csrf) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password))).andReturn();
    }

    Cookie anonymousCsrf() throws Exception {
        return mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    static String settingsJson(String issuer, String secretPart, boolean autoProvision, boolean localLogin) {
        return ("{\"issuerUrl\":\"%s\",\"clientId\":\"%s\"%s,\"scopes\":\"openid email profile groups\","
                + "\"groupsClaim\":\"groups\",\"roleMapping\":%s,\"autoProvision\":%s,\"localLoginEnabled\":%s}")
                .formatted(issuer, StubOidcProvider.CLIENT_ID, secretPart, ROLE_MAPPING, autoProvision, localLogin);
    }

    /** Saves a working configuration through the ADMIN API (secret included). */
    void configure(boolean autoProvision, boolean localLogin) throws Exception {
        Login admin = login("admin");
        // saved with local login on first: the validation forbids turning it off before OIDC is configured
        var result = mvc.perform(admin.write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/settings/oidc")).contentType(APPLICATION_JSON)
                .content(settingsJson(provider.issuer(), ",\"clientSecret\":\"" + StubOidcProvider.CLIENT_SECRET + "\"",
                        autoProvision, localLogin))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
    }

    private static final java.util.concurrent.atomic.AtomicInteger NEXT_ADDRESS =
            new java.util.concurrent.atomic.AtomicInteger();

    /** GET authorize from a fresh client address, so the per-address rate limit does not couple unrelated tests. */
    org.springframework.test.web.servlet.ResultActions authorize() throws Exception {
        int n = NEXT_ADDRESS.incrementAndGet();
        return mvc.perform(get("/api/auth/oidc/authorize").with(req -> {
            req.setRemoteAddr("10.9." + (n / 250) + "." + (n % 250 + 1));
            return req;
        }));
    }

    static Map<String, String> query(String location) {
        Map<String, String> q = new HashMap<>();
        UriComponentsBuilder.fromUri(URI.create(location)).build().getQueryParams()
                .forEach((k, v) -> q.put(k, java.net.URLDecoder.decode(v.get(0), java.nio.charset.StandardCharsets.UTF_8)));
        return q;
    }

    static Map<String, Object> claims(String sub, String username, String... groups) {
        Map<String, Object> c = new HashMap<>();
        c.put("sub", sub);
        c.put("preferred_username", username);
        c.put("email", username + "@example.com");
        c.put("groups", java.util.List.of(groups));
        return c;
    }

    /** Result of one browser pass through authorize and callback. */
    record Flow(MvcResult authorize, Map<String, String> authorizeQuery, MockHttpSession session, MvcResult callback) {

        String callbackLocation() {
            return callback.getResponse().getHeader("Location");
        }
    }

    /** Authorize, let the stub issue a code for {@code claims}, call back with the right state. */
    Flow signIn(Map<String, Object> claims) throws Exception {
        MvcResult authorize = authorize().andReturn();
        assertThat(authorize.getResponse().getStatus()).isEqualTo(302);
        Map<String, String> q = query(authorize.getResponse().getHeader("Location"));
        String code = "code-" + UUID.randomUUID();
        provider.grant(code, q.get("code_challenge"), q.get("nonce"), claims);
        MockHttpSession session = (MockHttpSession) authorize.getRequest().getSession(false);
        MvcResult callback = mvc.perform(get("/api/auth/oidc/callback")
                .param("code", code).param("state", q.get("state")).session(session)).andReturn();
        return new Flow(authorize, q, session, callback);
    }

    String meJson(MockHttpSession session) throws Exception {
        return mvc.perform(get("/api/auth/me").session(session)).andReturn().getResponse().getContentAsString();
    }
}
