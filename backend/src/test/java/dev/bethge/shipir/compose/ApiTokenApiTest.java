package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.auth.token.TokenCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Personal API tokens: management through the session, authentication with {@code Authorization: Bearer shp_...}. */
@TestPropertySource(properties = {"shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "shipir.api-tokens.max-per-user=3"})
class ApiTokenApiTest extends DeploymentTestBase {

    private static final String TOKENS = "/api/account/tokens";

    @org.springframework.beans.factory.annotation.Autowired
    dev.bethge.shipir.auth.LoginThrottle throttle;

    private void resetThrottle() {
        ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(throttle, "entries")).clear();
    }

    @BeforeEach
    void resetTokens() {
        // The throttle is in-memory state shared by all tests of this context: bad-token tests would lock 127.0.0.1.
        resetThrottle();
        jdbc.update("DELETE FROM api_tokens");
        jdbc.update("UPDATE security_settings SET require_2fa_for_admins = FALSE");
        jdbc.update("UPDATE users SET disabled_at = NULL, role = upper(username) WHERE username IN ('admin', 'operator', 'viewer')");
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String createToken(Login l, String username) throws Exception {
        String body = mvc.perform(l.write(post(TOKENS)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"ci-" + username + "\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private String tokenOf(String username) throws Exception {
        return createToken(login(username), username);
    }

    private void insertToken(String username, String plain, Instant createdAt, Instant expiresAt) {
        jdbc.update("INSERT INTO api_tokens (user_id, name, token_hash, prefix, created_at, expires_at) "
                        + "SELECT id, 'seeded', ?, ?, ?, ? FROM users WHERE username = ?",
                TokenCodec.sha256Hex(plain), plain.substring(0, 8), Timestamp.from(createdAt),
                expiresAt == null ? null : Timestamp.from(expiresAt), username);
    }

    @Test
    void createWithoutStepUpIsRefusedAndCreatesNothing() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON).content("{\"name\":\"a\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_tokens", Integer.class)).isZero();
    }

    @Test
    void createWithWrongPasswordIsRefusedAndLockoutAnswers429EvenForTheRightPassword() throws Exception {
        Login admin = login("admin");
        String wrong = "{\"name\":\"a\",\"stepUp\":{\"password\":\"not-the-password\"}}";
        for (int i = 0; i < 4; i++) {
            mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON).content(wrong))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STEP_UP_INVALID"));
        }
        // the 5th wrong attempt starts the lockout; it is still answered as a failure
        mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON).content(wrong))
                .andExpect(status().isForbidden());
        mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("STEP_UP_THROTTLED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_tokens", Integer.class)).isZero();
    }

    @Test
    void theStepUpPasswordIsNeverEchoedOrAudited() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"a\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForList("SELECT coalesce(details::text, '') FROM audit_log", String.class))
                .noneMatch(d -> d.contains(PASSWORD));
    }

    @Test
    void createReturnsPlaintextOnceAndStoresOnlyTheSha256() throws Exception {
        Login admin = login("admin");
        String body = mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"deploy bot\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("deploy bot"))
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.prefix").exists())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");
        String prefix = JsonPath.read(body, "$.prefix");

        assertThat(token).matches("shp_[A-Za-z0-9_-]{43}");
        assertThat(prefix).isEqualTo(token.substring(0, 8));
        assertThat(jdbc.queryForObject("SELECT token_hash FROM api_tokens", String.class))
                .isEqualTo(TokenCodec.sha256Hex(token));
        assertThat(jdbc.queryForList("SELECT row_to_json(t)::text FROM api_tokens t", String.class))
                .noneMatch(row -> row.contains(token));
        assertThat(jdbc.queryForList("SELECT coalesce(details::text, '') FROM audit_log", String.class))
                .noneMatch(d -> d.contains(token) || d.contains(token.substring(8)));

        String list = mvc.perform(get(TOKENS).session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].prefix").value(prefix))
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(token);
    }

    @Test
    void listShowsOnlyTheOwnTokens() throws Exception {
        tokenOf("admin");
        Login op = login("operator");
        createToken(op, "operator");

        mvc.perform(get(TOKENS).session(op.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("ci-operator"));
    }

    @Test
    void createValidatesNameAndExpiry() throws Exception {
        Login admin = login("admin");
        for (String json : List.of("{}", "{\"name\":\"  \",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}", "{\"name\":\"" + "x".repeat(101) + "\"}",
                "{\"name\":\"a\",\"expiresAt\":\"2000-01-01T00:00:00Z\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}",
                "{\"name\":\"a\",\"expiresAt\":\"" + Instant.now().plus(Duration.ofDays(400)) + "\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}")) {
            mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_tokens", Integer.class)).isZero();
    }

    @Test
    void expiryDefaultsToTheMaximumLifetimeAndAnExplicitOneIsKept() throws Exception {
        Login admin = login("admin");
        Instant explicit = Instant.now().plus(Duration.ofDays(30));
        mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"a\",\"expiresAt\":\"" + explicit + "\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}")).andExpect(status().isCreated());
        mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"b\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}")).andExpect(status().isCreated());

        Instant a = jdbc.queryForObject("SELECT expires_at FROM api_tokens WHERE name = 'a'", Timestamp.class).toInstant();
        Instant b = jdbc.queryForObject("SELECT expires_at FROM api_tokens WHERE name = 'b'", Timestamp.class).toInstant();
        assertThat(Duration.between(a, explicit).abs()).isLessThan(Duration.ofSeconds(2));
        assertThat(Duration.between(Instant.now().plus(Duration.ofDays(365)), b).abs()).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    void creatingRequiresTheCsrfToken() throws Exception {
        Login admin = login("admin");
        mvc.perform(post(TOKENS).session(admin.session()).contentType(APPLICATION_JSON).content("{\"name\":\"a\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(TOKENS)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenAuthenticatesReadsWithoutCsrfAndCreatesNoSession() throws Exception {
        String token = tokenOf("operator");

        MvcResult r = mvc.perform(bearer(get("/api/auth/me"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("operator"))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andReturn();
        assertThat(r.getRequest().getSession(false)).isNull();
        assertThat(r.getResponse().getCookie("JSESSIONID")).isNull();
    }

    @Test
    void tokenAuthenticatesWritesWithoutCsrfAsThatUser() throws Exception {
        String token = tokenOf("operator");

        mvc.perform(bearer(post(url()), token).contentType(APPLICATION_JSON)
                        .content(createJson("viatoken", YAML, null)))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT u.username FROM deployment_versions v JOIN users u ON u.id = v.created_by "
                + "LIMIT 1", String.class)).isEqualTo("operator");
        assertThat(jdbc.queryForList("SELECT username FROM audit_log WHERE action = 'DEPLOYMENT_CREATE'", String.class))
                .containsExactly("operator");
    }

    @Test
    void viewerTokenCannotOperate() throws Exception {
        String token = tokenOf("viewer");

        mvc.perform(bearer(get(url()), token)).andExpect(status().isOk());
        mvc.perform(bearer(post(url()), token).contentType(APPLICATION_JSON)
                        .content(createJson("nope", YAML, null)))
                .andExpect(status().isForbidden());
        mvc.perform(bearer(get("/api/audit-log"), token)).andExpect(status().isForbidden());
    }

    @Test
    void permissionLimitedOperatorTokenCannotSeeOtherHosts() throws Exception {
        UUID other = jdbc.queryForObject("INSERT INTO docker_hosts (name, connection_type, host, port, owner_user_id) "
                + "SELECT 'Other host', 'TCP', 'other.local', 2375, id FROM users WHERE username = 'admin' RETURNING id", UUID.class);
        String token = tokenOf("operator");

        mvc.perform(bearer(get("/api/hosts/" + other + "/managed-deployments"), token))
                .andExpect(status().isNotFound());
        mvc.perform(bearer(get("/api/hosts"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + other + "')]").isEmpty());
    }

    @Test
    void expiredTokenIs401() throws Exception {
        String plain = TokenCodec.newToken("shp_");
        insertToken("admin", plain, Instant.now().minus(Duration.ofDays(10)), Instant.now().minusSeconds(5));

        mvc.perform(bearer(get("/api/auth/me"), plain))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void tokenWithoutExpiryInTheDatabaseStillWorks() throws Exception {
        String plain = TokenCodec.newToken("shp_");
        insertToken("admin", plain, Instant.now().minusSeconds(60), null);

        mvc.perform(bearer(get("/api/auth/me"), plain)).andExpect(status().isOk());
    }

    @Test
    void unknownTokenIs401WithTheSameBodyAsNoAuthentication() throws Exception {
        String anonymous = mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknown = mvc.perform(bearer(get("/api/auth/me"), TokenCodec.newToken("shp_")))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        assertThat(unknown).isEqualTo(anonymous);
    }

    @Test
    void revokedTokenIs401AndOnlyTheOwnerMayRevoke() throws Exception {
        Login admin = login("admin");
        String body = mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON).content("{\"name\":\"a\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");
        String id = JsonPath.read(body, "$.id");
        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isOk());

        Login op = login("operator");
        mvc.perform(op.write(delete(TOKENS + "/" + id))).andExpect(status().isNotFound());
        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isOk());

        mvc.perform(admin.write(delete(TOKENS + "/" + id))).andExpect(status().isNoContent());
        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isUnauthorized());
        mvc.perform(admin.write(delete(TOKENS + "/" + id))).andExpect(status().isNotFound());
        mvc.perform(admin.write(delete(TOKENS + "/not-a-uuid"))).andExpect(status().isBadRequest());
    }

    @Test
    void disabledUsersTokenIs401AndWorksAgainWhenEnabled() throws Exception {
        String token = tokenOf("operator");
        jdbc.update("UPDATE users SET disabled_at = now() WHERE username = 'operator'");

        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isUnauthorized());
        mvc.perform(bearer(get(url()), token)).andExpect(status().isUnauthorized());

        jdbc.update("UPDATE users SET disabled_at = NULL WHERE username = 'operator'");
        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isOk());
    }

    @Test
    void roleChangeAppliesToExistingTokensImmediately() throws Exception {
        String token = tokenOf("operator");
        jdbc.update("UPDATE users SET role = 'VIEWER' WHERE username = 'operator'");

        mvc.perform(bearer(post(url()), token).contentType(APPLICATION_JSON)
                        .content(createJson("x", YAML, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTokenCannotManageTokens() throws Exception {
        Login admin = login("admin");
        String body = mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON).content("{\"name\":\"a\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");
        String id = JsonPath.read(body, "$.id");

        mvc.perform(bearer(get(TOKENS), token)).andExpect(status().isForbidden());
        mvc.perform(bearer(post(TOKENS), token).contentType(APPLICATION_JSON).content("{\"name\":\"b\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}"))
                .andExpect(status().isForbidden());
        mvc.perform(bearer(delete(TOKENS + "/" + id), token)).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    void malformedAuthorizationHeadersAreUnauthorized() throws Exception {
        String valid = tokenOf("admin");
        List<String> headers = List.of("", " ", "Bearer", "Bearer ", "Bearer   ", "Bearer x", "Bearer shp_",
                "Bearer shp_short", "Bearer " + "a".repeat(47), "Bearer ghp_" + "a".repeat(43),
                "Bearer " + "shp_" + "a".repeat(10_000), "Bearer " + valid + "x", "Bearer " + valid.substring(1),
                "Bearer " + valid + " " + valid, "Basic YWRtaW46YWRtaW4=", "Token " + valid, valid, "shp_ " + valid);
        for (String header : headers) {
            resetThrottle(); // each bad header is a failure; see the throttle tests for the lockout itself
            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, header))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void schemeIsCaseInsensitive() throws Exception {
        String token = tokenOf("admin");

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void aBadBearerTokenDoesNotFallBackToTheSession() throws Exception {
        Login admin = login("admin");

        mvc.perform(bearer(get("/api/auth/me"), TokenCodec.newToken("shp_")).session(admin.session()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lastUsedIsOnlyWrittenWhenOlderThanTheResolution() throws Exception {
        String token = tokenOf("admin");
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM api_tokens", Timestamp.class)).isNull();

        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isOk());
        Timestamp first = jdbc.queryForObject("SELECT last_used_at FROM api_tokens", Timestamp.class);
        assertThat(first).isNotNull();

        Timestamp recent = Timestamp.from(Instant.now().minus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.MICROS));
        jdbc.update("UPDATE api_tokens SET last_used_at = ?", recent);
        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM api_tokens", Timestamp.class)).isEqualTo(recent);

        Timestamp stale = Timestamp.from(Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS));
        jdbc.update("UPDATE api_tokens SET last_used_at = ?", stale);
        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM api_tokens", Timestamp.class)).isAfter(stale);
    }

    @Test
    void createAndRevokeAreAuditedWithThePrefixButNeverTheToken() throws Exception {
        Login admin = login("admin");
        String body = mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON).content("{\"name\":\"a\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");
        String id = JsonPath.read(body, "$.id");
        mvc.perform(admin.write(delete(TOKENS + "/" + id))).andExpect(status().isNoContent());

        assertThat(jdbc.queryForList("SELECT action || ':' || outcome || ':' || username || ':' || target_id FROM audit_log "
                + "WHERE action LIKE 'API\\_TOKEN\\_%' ORDER BY occurred_at, id", String.class))
                .containsExactly("API_TOKEN_CREATE:SUCCESS:admin:" + id, "API_TOKEN_REVOKE:SUCCESS:admin:" + id);
        assertThat(jdbc.queryForObject("SELECT details->>'prefix' FROM audit_log WHERE action = 'API_TOKEN_CREATE'",
                String.class)).isEqualTo(token.substring(0, 8));
        assertThat(jdbc.queryForList("SELECT row_to_json(a)::text FROM audit_log a", String.class))
                .noneMatch(row -> row.contains(token));
    }

    @Test
    void deletingTheUserRemovesTheirTokens() throws Exception {
        tokenOf("operator");
        jdbc.update("DELETE FROM users WHERE username = 'operator'");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_tokens", Integer.class)).isZero();
    }

    @Test
    void tokenOfAUserWithPendingPasswordSetupIsGatedLikeTheirSession() throws Exception {
        String token = tokenOf("operator");
        jdbc.update("UPDATE users SET password_changed_at = NULL WHERE username = 'operator'");

        mvc.perform(bearer(get("/api/hosts"), token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SETUP_REQUIRED"));
        mvc.perform(bearer(get(url()), token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SETUP_REQUIRED"));
        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isOk());
    }

    @Test
    void tokenOfAnAdminWithoutRequiredTwoFactorIsGated() throws Exception {
        String token = tokenOf("admin");
        jdbc.update("UPDATE security_settings SET require_2fa_for_admins = TRUE");

        mvc.perform(bearer(get("/api/hosts"), token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SETUP_REQUIRED"));
        mvc.perform(bearer(get(url()), token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SETUP_REQUIRED"));
        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isOk());
    }

    @Test
    void passwordChangeWithATokenCreatesNoSession() throws Exception {
        String token = tokenOf("operator");

        MvcResult r = mvc.perform(bearer(post("/api/auth/password"), token).contentType(APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Another-Str0ng-Passw0rd-42!\"}"))
                .andExpect(status().isNoContent()).andReturn();

        assertThat(r.getRequest().getSession(false)).isNull();
        assertThat(r.getResponse().getCookie("JSESSIONID")).isNull();
    }

    @Test
    void anAdminTokenIsCappedAtOperatorAndNeverReachesAdminOnlyRoutes() throws Exception {
        String token = tokenOf("admin");
        String json = "{\"passphrase\":\"a-long-enough-passphrase\"}";

        mvc.perform(bearer(get("/api/users"), token)).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/api/teams"), token)).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/api/audit-log"), token)).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/api/settings/ai"), token)).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/api/settings/security"), token)).andExpect(status().isForbidden());
        mvc.perform(bearer(post("/api/backup"), token).contentType(APPLICATION_JSON).content(json))
                .andExpect(status().isForbidden());
        mvc.perform(bearer(post("/api/hosts/" + hostId + "/containers/abc/exec"), token).contentType(APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/api/hosts/" + hostId + "/containers/abc/files"), token)).andExpect(status().isForbidden());
        mvc.perform(bearer(post("/api/hosts/" + hostId + "/images/build"), token)).andExpect(status().isForbidden());
        mvc.perform(bearer(post("/api/hosts"), token).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminTokenKeepsOperatorLevelAccessWhereTheUserIsGranted() throws Exception {
        grantHost("admin", "OPERATE");
        String token = tokenOf("admin");

        mvc.perform(bearer(get("/api/hosts"), token)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + hostId + "')]").isNotEmpty());
        mvc.perform(bearer(post(url()), token).contentType(APPLICATION_JSON)
                        .content(createJson("granted", YAML, null)))
                .andExpect(status().isCreated());
    }

    @Test
    void anAdminTokenGetsNoImplicitAccessToHostsWithoutGrant() throws Exception {
        String token = tokenOf("admin");

        mvc.perform(bearer(get("/api/hosts"), token)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + hostId + "')]").isEmpty());
        mvc.perform(bearer(get(url()), token)).andExpect(status().isNotFound());
        // the same user with a session is ADMIN and sees everything
        mvc.perform(get(url()).session(login("admin").session())).andExpect(status().isOk());
    }

    @Test
    void anAdminTokenCannotDeployHostLevelComposeFeatures() throws Exception {
        grantHost("admin", "OPERATE");
        String token = tokenOf("admin");
        String privileged = "services:\n  web:\n    image: nginx\n    privileged: true\n";

        mvc.perform(bearer(post(url()), token).contentType(APPLICATION_JSON)
                        .content(createJson("bytoken", privileged, null)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        // the same user in an interactive session may
        mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("bysession", privileged, null)))
                .andExpect(status().isCreated());
    }

    @Test
    void tokensAreRefusedAtTheWebSocketHandshake() throws Exception {
        for (String user : List.of("admin", "operator", "viewer")) {
            mvc.perform(bearer(get("/ws"), tokenOf(user))).andExpect(status().isForbidden());
        }
    }

    @Test
    void theTokenCountPerUserIsCapped() throws Exception {
        Login admin = login("admin");
        for (int i = 0; i < 3; i++) {
            mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON).content("{\"name\":\"t" + i + "\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}"))
                    .andExpect(status().isCreated());
        }

        mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON).content("{\"name\":\"one-too-many\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOKEN_LIMIT_REACHED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_tokens", Integer.class)).isEqualTo(3);
        // the cap is per user, and revoking frees a slot
        mvc.perform(login("operator").write(post(TOKENS)).contentType(APPLICATION_JSON).content("{\"name\":\"x\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}"))
                .andExpect(status().isCreated());
        String id = jdbc.queryForObject("SELECT id FROM api_tokens WHERE name = 't0'", UUID.class).toString();
        mvc.perform(admin.write(delete(TOKENS + "/" + id))).andExpect(status().isNoContent());
        mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON).content("{\"name\":\"fits-again\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}"))
                .andExpect(status().isCreated());
    }

    @Test
    void parallelCreationsCannotExceedTheCap() throws Exception {
        Login admin = login("admin");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String name = "p" + i;
                results.add(pool.submit(() -> mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}")).andReturn().getResponse().getStatus()));
            }
            for (var r : results) {
                r.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_tokens", Integer.class)).isEqualTo(3);
    }

    @Test
    void sessionPasswordChangeRevokesAllOfTheUsersTokens() throws Exception {
        String token = tokenOf("operator");
        tokenOf("admin");
        Login op = login("operator");

        mvc.perform(op.write(post("/api/auth/password")).contentType(APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Another-Str0ng-Passw0rd-42!\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_tokens t JOIN users u ON u.id = t.user_id "
                + "WHERE u.username = 'operator'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    void passwordChangeWithATokenRevokesThatTokenToo() throws Exception {
        String token = tokenOf("operator");

        mvc.perform(bearer(post("/api/auth/password"), token).contentType(APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Another-Str0ng-Passw0rd-42!\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isUnauthorized());
    }

    @Test
    void aFailedPasswordChangeKeepsTheTokens() throws Exception {
        String token = tokenOf("operator");

        mvc.perform(login("operator").write(post("/api/auth/password")).contentType(APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-current-password\",\"newPassword\":\"Another-Str0ng-Passw0rd-42!\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isOk());
    }

    @Test
    void failedBearerAttemptsLockTheAddressForRejectedCredentialsOnlyAndAreAuditedOnceWithoutSecrets() throws Exception {
        String valid = tokenOf("operator");
        String address = "203.0.113." + (1 + (int) (Math.random() * 200));
        String guess = TokenCodec.newToken("shp_");

        for (int i = 0; i < 5; i++) {
            mvc.perform(bearer(get("/api/auth/me"), guess).with(from(address))).andExpect(status().isUnauthorized());
        }
        MvcResult locked = mvc.perform(bearer(get("/api/auth/me"), guess).with(from(address)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS")).andReturn();
        mvc.perform(bearer(get("/api/auth/me"), TokenCodec.newToken("shp_")).with(from(address)))
                .andExpect(status().isTooManyRequests());
        // a valid token is never refused, not even from a locked address
        mvc.perform(bearer(get("/api/auth/me"), valid).with(from(address))).andExpect(status().isOk());

        assertThat(locked.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        mvc.perform(bearer(get("/api/auth/me"), valid).with(from("198.51.100.7"))).andExpect(status().isOk());
        List<String> rows = jdbc.queryForList("SELECT details::text FROM audit_log WHERE action = 'LOGIN_THROTTLED' "
                + "AND username = ?", String.class, address);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).contains("BEARER").contains(address).doesNotContain(guess).doesNotContain(valid);
    }

    @Test
    void aTokensAiChatEndpointsAreSessionOnly() throws Exception {
        grantHost("admin", "OPERATE");
        String token = tokenOf("admin");
        String id = UUID.randomUUID().toString();

        mvc.perform(bearer(post("/api/ai/sessions/" + id + "/messages"), token).contentType(APPLICATION_JSON)
                .content("{\"content\":\"hi\"}")).andExpect(status().isForbidden());
        mvc.perform(bearer(post("/api/ai/sessions/" + id + "/actions/" + id + "/confirm"), token))
                .andExpect(status().isForbidden());
        mvc.perform(bearer(post("/api/ai/sessions"), token).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(bearer(get("/api/ai/sessions"), token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/ai/sessions").session(login("admin").session())).andExpect(status().isOk());
    }

    @Test
    void meReportsTheEffectiveRoleOfATokenButTheStoredRoleOfASession() throws Exception {
        String token = tokenOf("admin");

        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(jsonPath("$.role").value("OPERATOR"));
        mvc.perform(get("/api/auth/me").session(login("admin").session())).andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    dev.bethge.shipir.auth.token.ApiTokenService tokenService;
    @org.springframework.beans.factory.annotation.Autowired
    dev.bethge.shipir.auth.token.ApiTokenRepository tokenRepository;
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;

    @Test
    void revokeAllWaitsForAnInFlightCreationOfTheSameUser() throws Exception {
        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'operator'", UUID.class);
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                tokenRepository.lockUser(userId); // a creation in flight holds this lock until it commits
                var revoke = pool.submit(() -> tokenService.revokeAllOf(userId));
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                assertThat(revoke.isDone()).as("revokeAllOf must wait for the lock").isFalse();
                holder = revoke;
            });
            holder.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private java.util.concurrent.Future<?> holder;

    @Test
    void malformedAndForeignBearerCredentialsCountAsFailuresToo() throws Exception {
        String address = "203.0.113." + (1 + (int) (Math.random() * 200));
        for (String junk : List.of("x", "shp_short", "a".repeat(5000), "ghp_" + "a".repeat(43), "")) {
            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + junk).with(from(address)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(bearer(get("/api/auth/me"), TokenCodec.newToken("shp_")).with(from(address)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void validTokensAndRequestsWithoutBearerNeverCountAsFailures() throws Exception {
        String valid = tokenOf("operator");
        String address = "203.0.113." + (1 + (int) (Math.random() * 200));

        for (int i = 0; i < 12; i++) {
            mvc.perform(bearer(get("/api/auth/me"), valid).with(from(address))).andExpect(status().isOk());
            mvc.perform(get("/api/auth/me").with(from(address))).andExpect(status().isUnauthorized());
        }
        mvc.perform(bearer(get("/api/auth/me"), valid).with(from(address))).andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
