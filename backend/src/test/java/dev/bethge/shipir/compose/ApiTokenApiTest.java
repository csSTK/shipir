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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Personal API tokens: management through the session, authentication with {@code Authorization: Bearer shp_...}. */
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class ApiTokenApiTest extends DeploymentTestBase {

    private static final String TOKENS = "/api/account/tokens";

    @BeforeEach
    void resetTokens() {
        jdbc.update("DELETE FROM api_tokens");
        jdbc.update("UPDATE security_settings SET require_2fa_for_admins = FALSE");
        jdbc.update("UPDATE users SET disabled_at = NULL, role = upper(username) WHERE username IN ('admin', 'operator', 'viewer')");
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String createToken(Login l, String username) throws Exception {
        String body = mvc.perform(l.write(post(TOKENS)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"ci-" + username + "\"}"))
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
    void createReturnsPlaintextOnceAndStoresOnlyTheSha256() throws Exception {
        Login admin = login("admin");
        String body = mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"deploy bot\"}"))
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
        for (String json : List.of("{}", "{\"name\":\"  \"}", "{\"name\":\"" + "x".repeat(101) + "\"}",
                "{\"name\":\"a\",\"expiresAt\":\"2000-01-01T00:00:00Z\"}",
                "{\"name\":\"a\",\"expiresAt\":\"" + Instant.now().plus(Duration.ofDays(400)) + "\"}")) {
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
                .content("{\"name\":\"a\",\"expiresAt\":\"" + explicit + "\"}")).andExpect(status().isCreated());
        mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"b\"}")).andExpect(status().isCreated());

        Instant a = jdbc.queryForObject("SELECT expires_at FROM api_tokens WHERE name = 'a'", Timestamp.class).toInstant();
        Instant b = jdbc.queryForObject("SELECT expires_at FROM api_tokens WHERE name = 'b'", Timestamp.class).toInstant();
        assertThat(Duration.between(a, explicit).abs()).isLessThan(Duration.ofSeconds(2));
        assertThat(Duration.between(Instant.now().plus(Duration.ofDays(365)), b).abs()).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    void creatingRequiresTheCsrfToken() throws Exception {
        Login admin = login("admin");
        mvc.perform(post(TOKENS).session(admin.session()).contentType(APPLICATION_JSON).content("{\"name\":\"a\"}"))
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
        String body = mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON).content("{\"name\":\"a\"}"))
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
        String body = mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON).content("{\"name\":\"a\"}"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");
        String id = JsonPath.read(body, "$.id");

        mvc.perform(bearer(get(TOKENS), token)).andExpect(status().isForbidden());
        mvc.perform(bearer(post(TOKENS), token).contentType(APPLICATION_JSON).content("{\"name\":\"b\"}"))
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

        Timestamp recent = Timestamp.from(Instant.now().minus(Duration.ofMinutes(1)));
        jdbc.update("UPDATE api_tokens SET last_used_at = ?", recent);
        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM api_tokens", Timestamp.class)).isEqualTo(recent);

        Timestamp stale = Timestamp.from(Instant.now().minus(Duration.ofHours(1)));
        jdbc.update("UPDATE api_tokens SET last_used_at = ?", stale);
        mvc.perform(bearer(get("/api/auth/me"), token)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM api_tokens", Timestamp.class)).isAfter(stale);
    }

    @Test
    void createAndRevokeAreAuditedWithThePrefixButNeverTheToken() throws Exception {
        Login admin = login("admin");
        String body = mvc.perform(admin.write(post(TOKENS)).contentType(APPLICATION_JSON).content("{\"name\":\"a\"}"))
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
    void tokenRequestsAreAuthenticatedOnAsyncDispatchToo() throws Exception {
        String token = tokenOf("admin");

        MvcResult started = mvc.perform(bearer(post("/api/backup"), token).contentType(APPLICATION_JSON)
                .content("{\"passphrase\":\"a-long-enough-passphrase\"}")).andReturn();
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        assertThat(started.getRequest().isAsyncStarted()).isTrue();

        MvcResult done = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                .andReturn();
        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        assertThat(done.getResponse().getContentAsByteArray()).isNotEmpty();
    }
}
