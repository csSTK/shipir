package dev.bethge.shipir.auth.totp;

import dev.bethge.shipir.PostgresTestConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** TOTP enrolment, login second step, disable, admin reset and the "require 2FA for admins" setting. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class TwoFactorApiTest {

    private static final String ADMIN_PASSWORD = "test-initial-admin-password";
    private static final String PW = "Some-Long-Passw0rd-123!";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JsonMapper json;
    @Autowired
    private TwoFactorAttemptLimiter limiter;

    @BeforeEach
    void reset() {
        limiter.reset("admin");
        limiter.reset("oper");
        jdbc.update("DELETE FROM user_recovery_codes");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("DELETE FROM users WHERE username <> 'admin'");
        jdbc.update("UPDATE security_settings SET require_2fa_for_admins = FALSE");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'base', 'LOCAL', id FROM users WHERE username = 'admin'");
        jdbc.update("UPDATE users SET password_hash = ?, password_changed_at = now(), disabled_at = NULL, role = 'ADMIN', "
                + "totp_secret_ciphertext = NULL, totp_enabled_at = NULL, totp_last_used_step = NULL "
                + "WHERE username = 'admin'", encoder.encode(ADMIN_PASSWORD));
    }

    private record Session(MockHttpSession session, Cookie csrf, JsonNode body, int status) {
    }

    private Cookie csrf() throws Exception {
        return mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private Session login(String user, String password) throws Exception {
        Cookie c = csrf();
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(c).header("X-XSRF-TOKEN", c.getValue())
                .contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", user, "password", password)))).andReturn();
        String body = r.getResponse().getContentAsString();
        return new Session((MockHttpSession) r.getRequest().getSession(false), c,
                body.isEmpty() ? null : json.readTree(body), r.getResponse().getStatus());
    }

    private MvcResult send(Session s, String method, String url, String body) throws Exception {
        var b = switch (method) {
            case "GET" -> get(url);
            case "PUT" -> put(url);
            default -> post(url);
        };
        if (s.session != null) {
            b.session(s.session);
        }
        b.cookie(s.csrf).header("X-XSRF-TOKEN", s.csrf.getValue());
        if (body != null) {
            b.contentType(APPLICATION_JSON).content(body);
        }
        return mvc.perform(b).andReturn();
    }

    private int status(Session s, String method, String url, String body) throws Exception {
        return send(s, method, url, body).getResponse().getStatus();
    }

    private JsonNode body(MvcResult r) throws Exception {
        String content = r.getResponse().getContentAsString();
        return content.isEmpty() ? null : json.readTree(content);
    }

    private String codeJson(String code) {
        return "{\"code\":\"%s\"}".formatted(code);
    }

    private static long now() {
        return Totp.stepAt(Instant.now());
    }

    /** Enrols the user (already logged in) and returns {secret bytes, recovery codes}. */
    private record Enrolment(byte[] secret, List<String> recoveryCodes) {
    }

    private Enrolment enrol(Session s) throws Exception {
        MvcResult setup = send(s, "POST", "/api/auth/2fa/setup", null);
        assertThat(setup.getResponse().getStatus()).isEqualTo(200);
        byte[] secret = Totp.fromBase32(body(setup).get("secret").asString());
        MvcResult confirm = send(s, "POST", "/api/auth/2fa/confirm", codeJson(Totp.code(secret, now())));
        assertThat(confirm.getResponse().getStatus()).as(confirm.getResponse().getContentAsString()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        body(confirm).get("recoveryCodes").forEach(n -> codes.add(n.asString()));
        return new Enrolment(secret, codes);
    }

    private long auditCount(String action, String outcome) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND outcome = ?",
                Long.class, action, outcome);
    }

    @Test
    void setupReturnsSecretAndUriAndConfirmEnablesWithTenHashedRecoveryCodes(CapturedOutput output) throws Exception {
        Session s = login("admin", ADMIN_PASSWORD);
        MvcResult setup = send(s, "POST", "/api/auth/2fa/setup", null);
        JsonNode setupBody = body(setup);
        String secret = setupBody.get("secret").asString();
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(setupBody.get("otpauthUri").asString())
                .startsWith("otpauth://totp/Shipir:admin?").contains("secret=" + secret).contains("issuer=Shipir");
        assertThat(jdbc.queryForObject("SELECT totp_enabled_at IS NULL FROM users WHERE username='admin'", Boolean.class))
                .isTrue();

        assertThat(status(s, "POST", "/api/auth/2fa/confirm", codeJson("000000"))).isEqualTo(400);
        MvcResult confirm = send(s, "POST", "/api/auth/2fa/confirm",
                codeJson(Totp.code(Totp.fromBase32(secret), now())));
        assertThat(confirm.getResponse().getStatus()).isEqualTo(200);
        JsonNode codes = body(confirm).get("recoveryCodes");
        assertThat(codes).hasSize(10);
        assertThat(jdbc.queryForObject("SELECT totp_enabled_at IS NOT NULL FROM users WHERE username='admin'", Boolean.class))
                .isTrue();
        // hashes only, secret only as ciphertext
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_recovery_codes", Long.class)).isEqualTo(10);
        for (JsonNode c : codes) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_recovery_codes WHERE code_hash = ?",
                    Long.class, c.asString())).isZero();
        }
        byte[] stored = jdbc.queryForObject("SELECT totp_secret_ciphertext FROM users WHERE username='admin'", byte[].class);
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(secret);
        assertThat(output.getAll()).doesNotContain(secret);
        assertThat(auditCount("MFA_ENABLE", "SUCCESS")).isEqualTo(1);

        // the secret is never returned again
        assertThat(status(s, "POST", "/api/auth/2fa/setup", null)).isEqualTo(409);
        assertThat(send(s, "GET", "/api/auth/me", null).getResponse().getContentAsString())
                .doesNotContain(secret).contains("\"twoFactorEnabled\":true");
    }

    @Test
    void meExposesTwoFactorDisabledByDefault() throws Exception {
        Session s = login("admin", ADMIN_PASSWORD);
        assertThat(s.body.get("twoFactorEnabled").asBoolean()).isFalse();
        assertThat(body(send(s, "GET", "/api/auth/me", null)).get("twoFactorEnabled").asBoolean()).isFalse();
    }

    @Test
    void loginWithTwoFactorOnlyAdmitsVerifyAndLogoutUntilVerified() throws Exception {
        Enrolment e = enrol(login("admin", ADMIN_PASSWORD));
        Session half = login("admin", ADMIN_PASSWORD);
        assertThat(half.status).isEqualTo(200);
        assertThat(half.body.get("mfaRequired").asBoolean()).isTrue();
        assertThat(half.body.has("role")).isFalse();

        assertThat(status(half, "GET", "/api/auth/me", null)).isEqualTo(401);
        assertThat(status(half, "GET", "/api/hosts", null)).isEqualTo(401);
        assertThat(status(half, "GET", "/api/users", null)).isEqualTo(401);
        assertThat(status(half, "POST", "/api/auth/2fa/setup", null)).isEqualTo(401);
        assertThat(status(half, "POST", "/api/auth/2fa/confirm", codeJson("123456"))).isEqualTo(401);
        assertThat(status(half, "POST", "/api/auth/2fa/disable",
                "{\"password\":\"%s\",\"code\":\"123456\"}".formatted(ADMIN_PASSWORD))).isEqualTo(401);
        assertThat(status(half, "POST", "/api/auth/password",
                "{\"currentPassword\":\"x\",\"newPassword\":\"y\"}")).isEqualTo(401);

        MvcResult bad = send(half, "POST", "/api/auth/2fa/verify", codeJson("000000"));
        assertThat(bad.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(bad).get("code").asString()).isEqualTo("INVALID_CODE");
        assertThat(status(half, "GET", "/api/hosts", null)).isEqualTo(401);

        // step+1 is inside the window and newer than the step used for confirm
        MvcResult ok = send(half, "POST", "/api/auth/2fa/verify", codeJson(Totp.code(e.secret, now() + 1)));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(ok).get("username").asString()).isEqualTo("admin");
        assertThat(body(ok).get("role").asString()).isEqualTo("ADMIN");
        assertThat(status(half, "GET", "/api/auth/me", null)).isEqualTo(200);
        assertThat(status(half, "GET", "/api/hosts", null)).isEqualTo(200);
        assertThat(auditCount("MFA_VERIFY", "FAILURE")).isEqualTo(1);
        assertThat(auditCount("MFA_VERIFY", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void verifyWithoutPendingLoginIsRejected() throws Exception {
        Session anonymous = new Session(null, csrf(), null, 0);
        assertThat(status(anonymous, "POST", "/api/auth/2fa/verify", codeJson("123456"))).isEqualTo(401);
        Session full = login("admin", ADMIN_PASSWORD);
        assertThat(status(full, "POST", "/api/auth/2fa/verify", codeJson("123456"))).isEqualTo(401);
    }

    @Test
    void halfAuthenticatedSessionCanLogOut() throws Exception {
        enrol(login("admin", ADMIN_PASSWORD));
        Session half = login("admin", ADMIN_PASSWORD);
        assertThat(status(half, "POST", "/api/auth/logout", null)).isEqualTo(204);
        assertThat(status(half, "POST", "/api/auth/2fa/verify", codeJson("123456"))).isEqualTo(401);
    }

    @Test
    void unreadableSecretAnswers409OnVerifyConfirmAndDisableInsteadOf500(CapturedOutput output) throws Exception {
        Enrolment e = enrol(login("admin", ADMIN_PASSWORD));
        Session half = login("admin", ADMIN_PASSWORD);
        // simulates a changed encryption key: stored ciphertext can no longer be decrypted
        jdbc.update("UPDATE users SET totp_secret_ciphertext = ? WHERE username = 'admin'", new byte[40]);
        String code = codeJson(Totp.code(e.secret, now() + 1));

        MvcResult verify = send(half, "POST", "/api/auth/2fa/verify", code);
        assertThat(verify.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(verify).get("code").asString()).isEqualTo("TWO_FACTOR_SECRET_UNREADABLE");
        assertThat(status(half, "GET", "/api/auth/me", null)).isEqualTo(401);
        // operators see the failure in the log (user named, no secret or ciphertext)
        assertThat(output.getAll()).contains("cannot be decrypted").contains("admin")
                .doesNotContain(Totp.toBase32(e.secret));

        // confirm: an enrolment in progress whose secret is unreadable
        jdbc.update("UPDATE users SET totp_enabled_at = NULL WHERE username = 'admin'");
        Session full = login("admin", ADMIN_PASSWORD);
        MvcResult confirm = send(full, "POST", "/api/auth/2fa/confirm", code);
        assertThat(confirm.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(confirm).get("code").asString()).isEqualTo("TWO_FACTOR_SECRET_UNREADABLE");

        // disable: enabled again with an unreadable secret
        jdbc.update("UPDATE users SET totp_enabled_at = now() WHERE username = 'admin'");
        Session full2 = new Session(full.session, full.csrf, null, 200);
        MvcResult disable = send(full2, "POST", "/api/auth/2fa/disable",
                "{\"password\":\"%s\",\"code\":\"123456\"}".formatted(ADMIN_PASSWORD));
        assertThat(disable.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(disable).get("code").asString()).isEqualTo("TWO_FACTOR_SECRET_UNREADABLE");
    }

    @Test
    void sameCodeCannotBeUsedTwice() throws Exception {
        Enrolment e = enrol(login("admin", ADMIN_PASSWORD));
        // confirm consumed the current step
        Session first = login("admin", ADMIN_PASSWORD);
        assertThat(status(first, "POST", "/api/auth/2fa/verify", codeJson(Totp.code(e.secret, now())))).isEqualTo(401);
        String next = Totp.code(e.secret, now() + 1);
        assertThat(status(first, "POST", "/api/auth/2fa/verify", codeJson(next))).isEqualTo(200);
        Session second = login("admin", ADMIN_PASSWORD);
        assertThat(status(second, "POST", "/api/auth/2fa/verify", codeJson(next))).isEqualTo(401);
    }

    @Test
    void acceptsPreviousAndNextStepButNotTwoAway() throws Exception {
        Enrolment e = enrol(login("admin", ADMIN_PASSWORD));
        jdbc.update("UPDATE users SET totp_last_used_step = NULL WHERE username = 'admin'");
        Session half = login("admin", ADMIN_PASSWORD);
        assertThat(status(half, "POST", "/api/auth/2fa/verify", codeJson(Totp.code(e.secret, now() + 3)))).isEqualTo(401);
        assertThat(status(half, "POST", "/api/auth/2fa/verify", codeJson(Totp.code(e.secret, now() - 3)))).isEqualTo(401);
        assertThat(status(half, "POST", "/api/auth/2fa/verify", codeJson(Totp.code(e.secret, now() - 1)))).isEqualTo(200);
    }

    @Test
    void recoveryCodeLogsInOnceAndIsConsumed() throws Exception {
        Enrolment e = enrol(login("admin", ADMIN_PASSWORD));
        String recovery = e.recoveryCodes.get(0);
        Session half = login("admin", ADMIN_PASSWORD);
        assertThat(status(half, "POST", "/api/auth/2fa/verify", codeJson(recovery))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_recovery_codes WHERE used_at IS NOT NULL", Long.class))
                .isEqualTo(1);
        Session again = login("admin", ADMIN_PASSWORD);
        assertThat(status(again, "POST", "/api/auth/2fa/verify", codeJson(recovery))).isEqualTo(401);
        // another unused code, typed lower case without dash, still works
        String other = e.recoveryCodes.get(1).replace("-", "").toLowerCase();
        assertThat(status(again, "POST", "/api/auth/2fa/verify", codeJson(other))).isEqualTo(200);
    }

    @Test
    void tooManyWrongCodesLockVerifyEvenForTheRightCode() throws Exception {
        Enrolment e = enrol(login("admin", ADMIN_PASSWORD));
        Session half = login("admin", ADMIN_PASSWORD);
        for (int i = 0; i < 5; i++) {
            assertThat(status(half, "POST", "/api/auth/2fa/verify", codeJson("000000"))).isEqualTo(401);
        }
        MvcResult locked = send(half, "POST", "/api/auth/2fa/verify", codeJson(Totp.code(e.secret, now() + 1)));
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(locked).get("code").asString()).isEqualTo("TOO_MANY_ATTEMPTS");
        // remaining lockout in whole seconds, never more than the 5 minute default lockout
        assertThat(retryAfterSeconds(locked)).isBetween(1L, 300L);
        // a fresh password login does not reset the counter
        Session again = login("admin", ADMIN_PASSWORD);
        assertThat(status(again, "POST", "/api/auth/2fa/verify", codeJson(Totp.code(e.secret, now() + 1)))).isEqualTo(429);
        // the five wrong codes (the last one starts the lockout) are audited; the blocked 429s write no rows
        assertThat(auditCount("MFA_VERIFY", "FAILURE")).isEqualTo(5);
    }

    @Test
    void confirmIsThrottledToo() throws Exception {
        Session s = login("admin", ADMIN_PASSWORD);
        send(s, "POST", "/api/auth/2fa/setup", null);
        for (int i = 0; i < 5; i++) {
            assertThat(status(s, "POST", "/api/auth/2fa/confirm", codeJson("000000"))).isEqualTo(400);
        }
        MvcResult locked = send(s, "POST", "/api/auth/2fa/confirm", codeJson("000000"));
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(retryAfterSeconds(locked)).isBetween(1L, 300L);
    }

    private static long retryAfterSeconds(MvcResult r) {
        String header = r.getResponse().getHeader("Retry-After");
        assertThat(header).as("Retry-After on 429").isNotNull();
        return Long.parseLong(header);
    }

    @Test
    void disableNeedsPasswordAndCodeAndClearsEverything() throws Exception {
        Session s = login("admin", ADMIN_PASSWORD);
        Enrolment e = enrol(s);
        String body = "{\"password\":\"%s\",\"code\":\"%s\"}";
        assertThat(status(s, "POST", "/api/auth/2fa/disable",
                body.formatted("wrong-password", Totp.code(e.secret, now() + 1)))).isEqualTo(400);
        assertThat(status(s, "POST", "/api/auth/2fa/disable",
                body.formatted(ADMIN_PASSWORD, "000000"))).isEqualTo(400);
        assertThat(status(s, "POST", "/api/auth/2fa/disable",
                body.formatted(ADMIN_PASSWORD, Totp.code(e.secret, now() + 1)))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT totp_secret_ciphertext IS NULL AND totp_enabled_at IS NULL "
                + "AND totp_last_used_step IS NULL FROM users WHERE username='admin'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_recovery_codes", Long.class)).isZero();
        assertThat(auditCount("MFA_DISABLE", "SUCCESS")).isEqualTo(1);
        assertThat(login("admin", ADMIN_PASSWORD).body.has("mfaRequired")).isFalse();
    }

    @Test
    void adminCanResetAnotherUsersTwoFactorButNotTheirOwn() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        jdbc.update("INSERT INTO users (username, password_hash, role, created_at, password_changed_at) "
                + "VALUES ('oper', ?, 'OPERATOR', now(), now())", encoder.encode(PW));
        jdbc.update("INSERT INTO users (username, password_hash, role, created_at, password_changed_at) "
                + "VALUES ('viewer1', ?, 'VIEWER', now(), now())", encoder.encode(PW));
        Session oper = login("oper", PW);
        enrol(oper);
        String operId = jdbc.queryForObject("SELECT id::text FROM users WHERE username='oper'", String.class);

        assertThat(status(oper, "POST", "/api/users/" + operId + "/reset-2fa", null)).isEqualTo(403);
        assertThat(status(admin, "POST", "/api/users/00000000-0000-0000-0000-000000000000/reset-2fa", null))
                .isEqualTo(404);
        String adminId = jdbc.queryForObject("SELECT id::text FROM users WHERE username='admin'", String.class);
        assertThat(status(admin, "POST", "/api/users/" + adminId + "/reset-2fa", null)).isEqualTo(400);

        assertThat(status(admin, "POST", "/api/users/" + operId + "/reset-2fa", null)).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT totp_enabled_at IS NULL FROM users WHERE username='oper'", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_recovery_codes", Long.class)).isZero();
        assertThat(auditCount("MFA_RESET", "SUCCESS")).isEqualTo(1);
        // the session of the reset user is ended, the next login needs no second step
        assertThat(status(oper, "GET", "/api/auth/me", null)).isEqualTo(401);
        assertThat(login("oper", PW).body.has("mfaRequired")).isFalse();
    }

    @Test
    void userListShowsWhetherTwoFactorIsOn() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        enrol(admin);
        JsonNode users = body(send(admin, "GET", "/api/users", null));
        assertThat(users.get(0).get("twoFactorEnabled").asBoolean()).isTrue();
    }

    @Test
    void securitySettingsAreAdminOnlyAuditedAndForceAdminEnrolment() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        jdbc.update("INSERT INTO users (username, password_hash, role, created_at, password_changed_at) "
                + "VALUES ('oper', ?, 'OPERATOR', now(), now())", encoder.encode(PW));
        Session oper = login("oper", PW);
        assertThat(status(oper, "GET", "/api/settings/security", null)).isEqualTo(403);
        assertThat(status(oper, "PUT", "/api/settings/security", "{\"require2faForAdmins\":true}")).isEqualTo(403);

        assertThat(body(send(admin, "GET", "/api/settings/security", null)).get("require2faForAdmins").asBoolean())
                .isFalse();
        MvcResult put = send(admin, "PUT", "/api/settings/security", "{\"require2faForAdmins\":true}");
        assertThat(put.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(put).get("require2faForAdmins").asBoolean()).isTrue();
        assertThat(auditCount("SECURITY_SETTINGS_UPDATE", "SUCCESS")).isEqualTo(1);

        // the admin has no 2FA yet: gate allows only setup/confirm/me/logout
        assertThat(body(send(admin, "GET", "/api/auth/me", null)).get("setupStep").asString()).isEqualTo("TWO_FACTOR");
        MvcResult blocked = send(admin, "GET", "/api/hosts", null);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(blocked).get("code").asString()).isEqualTo("SETUP_REQUIRED");
        assertThat(status(admin, "GET", "/api/settings/security", null)).isEqualTo(403);
        // operators are not affected
        assertThat(status(oper, "GET", "/api/hosts", null)).isEqualTo(200);

        enrol(admin);
        assertThat(body(send(admin, "GET", "/api/auth/me", null)).get("setupStep").isNull()).isTrue();
        assertThat(status(admin, "GET", "/api/hosts", null)).isEqualTo(200);
    }

    @Test
    void adminCannotDisableTwoFactorWhileItIsRequired() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        Enrolment e = enrol(admin);
        assertThat(status(admin, "PUT", "/api/settings/security", "{\"require2faForAdmins\":true}")).isEqualTo(200);
        MvcResult r = send(admin, "POST", "/api/auth/2fa/disable",
                "{\"password\":\"%s\",\"code\":\"%s\"}".formatted(ADMIN_PASSWORD, Totp.code(e.secret, now() + 1)));
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r).get("code").asString()).isEqualTo("TWO_FACTOR_REQUIRED");
    }

    @Test
    void parallelWrongCodesCannotExceedTheAttemptCap() throws Exception {
        Enrolment e = enrol(login("admin", ADMIN_PASSWORD));
        int threads = 16;
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            sessions.add(login("admin", ADMIN_PASSWORD));
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
        for (Session s : sessions) {
            results.add(pool.submit(() -> {
                start.await();
                return status(s, "POST", "/api/auth/2fa/verify", codeJson("000000"));
            }));
        }
        start.countDown();
        int wrong = 0;
        int locked = 0;
        for (var f : results) {
            int code = f.get();
            if (code == 401) {
                wrong++;
            } else if (code == 429) {
                locked++;
            }
        }
        pool.shutdown();
        assertThat(wrong).isEqualTo(5);
        assertThat(locked).isEqualTo(threads - 5);
        Session again = login("admin", ADMIN_PASSWORD);
        assertThat(status(again, "POST", "/api/auth/2fa/verify", codeJson(Totp.code(e.secret, now() + 1))))
                .isEqualTo(429);
    }

    @Test
    void passwordStepOfATwoFactorUserIsAuditedAsPendingNotAsCompletedLogin() throws Exception {
        Enrolment e = enrol(login("admin", ADMIN_PASSWORD));
        jdbc.update("DELETE FROM audit_log");
        Session half = login("admin", ADMIN_PASSWORD);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'LOGIN' AND outcome = 'SUCCESS' "
                + "AND details->>'mfaPending' = 'true'", Long.class)).isEqualTo(1);
        send(half, "POST", "/api/auth/2fa/verify", codeJson(Totp.code(e.secret, now() + 1)));
        jdbc.update("UPDATE users SET totp_secret_ciphertext = NULL, totp_enabled_at = NULL WHERE username = 'admin'");
        login("admin", ADMIN_PASSWORD);
        // a normal password-only login carries no mfaPending marker
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'LOGIN' "
                + "AND details IS NULL", Long.class)).isEqualTo(1);
    }

    @Test
    void pendingLoginExpiresAfterTheTtl() throws Exception {
        Enrolment e = enrol(login("admin", ADMIN_PASSWORD));
        Session half = login("admin", ADMIN_PASSWORD);
        half.session.setAttribute("shipir.mfa.pending.until", Instant.now().minusSeconds(1));
        assertThat(status(half, "POST", "/api/auth/2fa/verify", codeJson(Totp.code(e.secret, now() + 1))))
                .isEqualTo(401);
        assertThat(status(half, "GET", "/api/hosts", null)).isEqualTo(401);
    }

    @Test
    void userDisabledBetweenPasswordStepAndVerifyCannotFinishLogin() throws Exception {
        Enrolment e = enrol(login("admin", ADMIN_PASSWORD));
        Session half = login("admin", ADMIN_PASSWORD);
        jdbc.update("UPDATE users SET disabled_at = now() WHERE username = 'admin'");
        assertThat(status(half, "POST", "/api/auth/2fa/verify", codeJson(Totp.code(e.secret, now() + 1))))
                .isEqualTo(401);
        assertThat(status(half, "GET", "/api/auth/me", null)).isEqualTo(401);
    }

    @Test
    void twoFactorResetBetweenPasswordStepAndVerifyCannotFinishLogin() throws Exception {
        Enrolment e = enrol(login("admin", ADMIN_PASSWORD));
        Session half = login("admin", ADMIN_PASSWORD);
        jdbc.update("UPDATE users SET totp_secret_ciphertext = NULL, totp_enabled_at = NULL, "
                + "totp_last_used_step = NULL WHERE username = 'admin'");
        assertThat(status(half, "POST", "/api/auth/2fa/verify", codeJson(Totp.code(e.secret, now() + 1))))
                .isEqualTo(401);
        assertThat(status(half, "GET", "/api/auth/me", null)).isEqualTo(401);
    }

    @Test
    void startingAPendingLoginDropsTheSessionsPreviousAuthentication() throws Exception {
        enrol(login("admin", ADMIN_PASSWORD));
        jdbc.update("INSERT INTO users (username, password_hash, role, created_at, password_changed_at) "
                + "VALUES ('oper', ?, 'OPERATOR', now(), now())", encoder.encode(PW));
        Session asOper = login("oper", PW);
        assertThat(status(asOper, "GET", "/api/auth/me", null)).isEqualTo(200);
        MvcResult r = mvc.perform(post("/api/auth/login").session(asOper.session).cookie(asOper.csrf)
                .header("X-XSRF-TOKEN", asOper.csrf.getValue()).contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "admin", "password", ADMIN_PASSWORD)))).andReturn();
        assertThat(body(r).get("mfaRequired").asBoolean()).isTrue();
        assertThat(status(asOper, "GET", "/api/auth/me", null)).isEqualTo(401);
        assertThat(status(asOper, "GET", "/api/hosts", null)).isEqualTo(401);
    }

    @Test
    void disableAcceptsARecoveryCode() throws Exception {
        Session s = login("admin", ADMIN_PASSWORD);
        Enrolment e = enrol(s);
        assertThat(status(s, "POST", "/api/auth/2fa/disable",
                "{\"password\":\"%s\",\"code\":\"%s\"}".formatted(ADMIN_PASSWORD, e.recoveryCodes.get(0))))
                .isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT totp_enabled_at IS NULL FROM users WHERE username='admin'", Boolean.class))
                .isTrue();
    }

    @Test
    void auditEntriesNeverContainCodesOrSecrets() throws Exception {
        Session s = login("admin", ADMIN_PASSWORD);
        String secret = Totp.toBase32(Totp.fromBase32(
                body(send(s, "POST", "/api/auth/2fa/setup", null)).get("secret").asString()));
        byte[] key = Totp.fromBase32(secret);
        String confirmCode = Totp.code(key, now());
        List<String> recovery = new ArrayList<>(body(send(s, "POST", "/api/auth/2fa/confirm", codeJson(confirmCode)))
                .get("recoveryCodes").valueStream().map(JsonNode::asString).toList());
        Session half = login("admin", ADMIN_PASSWORD);
        String verifyCode = Totp.code(key, now() + 1);
        send(half, "POST", "/api/auth/2fa/verify", codeJson("111111"));
        send(half, "POST", "/api/auth/2fa/verify", codeJson(verifyCode));
        String disableCode = recovery.get(2);
        send(half, "POST", "/api/auth/2fa/disable",
                "{\"password\":\"%s\",\"code\":\"%s\"}".formatted(ADMIN_PASSWORD, disableCode));
        String all = jdbc.queryForObject("SELECT coalesce(string_agg(coalesce(details::text,'') || ' ' || username || ' ' "
                + "|| coalesce(target_id,'') || ' ' || coalesce(target_type,''), ' '), '') FROM audit_log", String.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action LIKE 'MFA_%'", Long.class))
                .isGreaterThanOrEqualTo(3);
        assertThat(all).doesNotContain(secret).doesNotContain(confirmCode).doesNotContain(verifyCode)
                .doesNotContain("111111").doesNotContain(disableCode.replace("-", ""));
        for (String c : recovery) {
            assertThat(all).doesNotContain(c);
        }
    }

    /** Runs every call at the same moment (after all threads are ready) and returns the HTTP statuses. */
    private List<Integer> concurrently(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelWrongVerifyCodesAreEvaluatedAtMostMaxAttemptsTimes() throws Exception {
        Enrolment e = enrol(login("admin", ADMIN_PASSWORD));
        List<Session> halves = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            halves.add(login("admin", ADMIN_PASSWORD));
        }
        List<Callable<Integer>> calls = new ArrayList<>();
        for (Session half : halves) {
            calls.add(() -> status(half, "POST", "/api/auth/2fa/verify", codeJson("000000")));
        }
        List<Integer> statuses = concurrently(calls);
        // 401 = the wrong code was actually evaluated, 429 = refused without evaluation
        assertThat(statuses.stream().filter(c -> c == 401).count()).isEqualTo(5);
        assertThat(statuses.stream().filter(c -> c == 429).count()).isEqualTo(15);
        assertThat(status(halves.get(0), "POST", "/api/auth/2fa/verify", codeJson(Totp.code(e.secret, now() + 1))))
                .isEqualTo(429);
    }

    @Test
    void parallelWrongDisableCodesAreEvaluatedAtMostMaxAttemptsTimes() throws Exception {
        Session full = login("admin", ADMIN_PASSWORD);
        enrol(full);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(() -> status(full, "POST", "/api/auth/2fa/disable",
                    "{\"password\":\"%s\",\"code\":\"000000\"}".formatted(ADMIN_PASSWORD)));
        }
        List<Integer> statuses = concurrently(calls);
        assertThat(statuses.stream().filter(c -> c == 400).count()).isEqualTo(5);
        assertThat(statuses.stream().filter(c -> c == 429).count()).isEqualTo(15);
    }

    @Test
    void parallelUseOfOneRecoveryCodeSucceedsOnlyOnce() throws Exception {
        Enrolment e = enrol(login("admin", ADMIN_PASSWORD));
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Session half = login("admin", ADMIN_PASSWORD);
            calls.add(() -> status(half, "POST", "/api/auth/2fa/verify", codeJson(e.recoveryCodes.get(0))));
        }
        assertThat(concurrently(calls).stream().filter(c -> c == 200).count()).isEqualTo(1);
    }

    @Test
    void parallelReplayOfOneTotpCodeSucceedsOnlyOnce() throws Exception {
        Enrolment e = enrol(login("admin", ADMIN_PASSWORD));
        String next = Totp.code(e.secret, now() + 1);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Session half = login("admin", ADMIN_PASSWORD);
            calls.add(() -> status(half, "POST", "/api/auth/2fa/verify", codeJson(next)));
        }
        assertThat(concurrently(calls).stream().filter(c -> c == 200).count()).isEqualTo(1);
    }
}
