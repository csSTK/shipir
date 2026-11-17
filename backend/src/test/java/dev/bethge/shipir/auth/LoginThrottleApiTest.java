package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.MutableClock;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Throttle on login and password reset (default settings: 5 attempts, 15 min window and lockout). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, LoginThrottleApiTest.TestBeans.class})
class LoginThrottleApiTest {

    private static final String PASSWORD = "Old-Passw0rd-for-tests!";

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2030-01-01T10:00:00Z"));
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private String username;
    private Cookie csrf;

    /**
     * Root cause of the flake (shipr-k31n.13/.15): JUnit creates a new test instance per test, so an instance
     * counter was always 1 and the peer address came only from {@code identityHashCode(this) % 200}. With ~20 tests
     * two of them regularly landed on the same address in the shared Spring context (limiter and clock are shared),
     * and the other test's failures (e.g. the 60 parallel attempts) pushed the address over its limit of 20, so the
     * spoof test saw 429 instead of 401. A static counter hands out every address exactly once per JVM.
     */
    private static final AtomicInteger PEER_SEQUENCE = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        username = "user-" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new User(username, encoder.encode(PASSWORD), Role.OPERATOR));
        csrf = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    /** A peer address no other call in this JVM has used, so tests never share an address counter. */
    private String peer() {
        int n = PEER_SEQUENCE.incrementAndGet();
        return "10." + (n / 65536 % 256) + "." + (n / 256 % 256) + "." + (n % 256);
    }

    private MvcResult send(String path, String json, String remoteAddr, String forwardedFor) throws Exception {
        MockHttpServletRequestBuilder request = post(path).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(APPLICATION_JSON).content(json).with(r -> {
                    r.setRemoteAddr(remoteAddr);
                    return r;
                });
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult login(String name, String pw, String remoteAddr) throws Exception {
        return send("/api/auth/login", "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(name, pw),
                remoteAddr, null);
    }

    @Test
    void peerAddressesAreNeverReusedAcrossTestInstances() {
        // Guards the root cause above: a fresh instance (as JUnit creates per test) must not repeat an address.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            assertThat(seen.add(peer())).as("address handed out twice").isTrue();
        }
    }

    @Test
    void sixthWrongLoginIsTooManyAttemptsWithRetryAfter() throws Exception {
        String ip = peer();
        for (int i = 0; i < 5; i++) {
            assertThat(login(username, "wrong", ip).getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult sixth = login(username, "wrong", ip);

        assertThat(sixth.getResponse().getStatus()).isEqualTo(429);
        assertThat(sixth.getResponse().getContentAsString()).contains("\"code\":\"TOO_MANY_ATTEMPTS\"");
        assertThat(sixth.getResponse().getHeader("Retry-After")).isEqualTo("900");
        // Correct password does not help while locked.
        assertThat(login(username, PASSWORD, ip).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void lockoutEndsAfterTheLockoutPeriod() throws Exception {
        String ip = peer();
        for (int i = 0; i < 5; i++) {
            login(username, "wrong", ip);
        }
        clock.advance(Duration.ofMinutes(16));

        assertThat(login(username, PASSWORD, ip).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void successResetsTheCounter() throws Exception {
        String ip = peer();
        for (int i = 0; i < 4; i++) {
            login(username, "wrong", ip);
        }
        assertThat(login(username, PASSWORD, ip).getResponse().getStatus()).isEqualTo(200);
        for (int i = 0; i < 4; i++) {
            assertThat(login(username, "wrong", ip).getResponse().getStatus()).isEqualTo(401);
        }
    }

    @Test
    void failuresOutsideTheWindowDoNotCount() throws Exception {
        String ip = peer();
        for (int i = 0; i < 4; i++) {
            login(username, "wrong", ip);
        }
        clock.advance(Duration.ofMinutes(16));
        assertThat(login(username, "wrong", ip).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(username, "wrong", ip).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void otherUsersAreUnaffected() throws Exception {
        String other = "other-" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new User(other, encoder.encode(PASSWORD), Role.OPERATOR));
        String ip = peer();
        for (int i = 0; i < 6; i++) {
            login(username, "wrong", ip);
        }

        assertThat(login(other, PASSWORD, ip).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void knownAndUnknownUserGetIdenticalResponses() throws Exception {
        String ghost = "ghost-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult known = null;
        MvcResult unknown = null;
        for (int i = 0; i < 6; i++) {
            known = login(username, "wrong", peer());
            unknown = login(ghost, "wrong", peer());
        }

        assertThat(known.getResponse().getStatus()).isEqualTo(429);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(429);
        assertThat(unknown.getResponse().getContentAsString()).isEqualTo(known.getResponse().getContentAsString());
        assertThat(unknown.getResponse().getHeader("Retry-After")).isEqualTo(known.getResponse().getHeader("Retry-After"));
    }

    @Test
    void spoofedForwardedForDoesNotEscapeTheAddressLimit() throws Exception {
        String ip = peer();
        // 20 failures for distinct usernames from one peer, each claiming a different forwarded address.
        for (int i = 0; i < 20; i++) {
            MvcResult r = send("/api/auth/login",
                    "{\"username\":\"spoof-%d-%s\",\"password\":\"x\"}".formatted(i, username), ip, "203.0.113." + i);
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult blocked = send("/api/auth/login",
                "{\"username\":\"spoof-new-%s\",\"password\":\"x\"}".formatted(username), ip, "203.0.113.99");

        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void passwordResetRequestIsThrottledForKnownAndUnknownUser() throws Exception {
        String ip = peer();
        String ghost = "ghost-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 5; i++) {
            assertThat(send("/api/auth/password-reset/request", "{\"username\":\"%s\"}".formatted(username), ip, null)
                    .getResponse().getStatus()).isEqualTo(202);
            assertThat(send("/api/auth/password-reset/request", "{\"username\":\"%s\"}".formatted(ghost), ip, null)
                    .getResponse().getStatus()).isEqualTo(202);
        }
        MvcResult known = send("/api/auth/password-reset/request", "{\"username\":\"%s\"}".formatted(username), ip, null);
        MvcResult unknown = send("/api/auth/password-reset/request", "{\"username\":\"%s\"}".formatted(ghost), ip, null);

        assertThat(known.getResponse().getStatus()).isEqualTo(429);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(429);
        assertThat(unknown.getResponse().getContentAsString()).isEqualTo(known.getResponse().getContentAsString());
    }

    @Test
    void passwordResetConfirmIsThrottledAfterWrongCodes() throws Exception {
        String ip = peer();
        String body = "{\"username\":\"%s\",\"code\":\"000000\",\"newPassword\":\"A-brand-new-Passw0rd!\"}"
                .formatted(username);
        for (int i = 0; i < 5; i++) {
            assertThat(send("/api/auth/password-reset/confirm", body, ip, null).getResponse().getStatus())
                    .isEqualTo(400);
        }

        assertThat(send("/api/auth/password-reset/confirm", body, ip, null).getResponse().getStatus())
                .isEqualTo(429);
    }

    @Test
    void lockoutIsAuditedOnceWithoutSecrets() throws Exception {
        String ip = peer();
        for (int i = 0; i < 8; i++) {
            login(username, "wrong-secret-xyz", ip);
        }

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'LOGIN_THROTTLED' AND username = ?",
                Integer.class, username);
        String details = jdbc.queryForObject(
                "SELECT details::text FROM audit_log WHERE action = 'LOGIN_THROTTLED' AND username = ?",
                String.class, username);
        assertThat(rows).isEqualTo(1);
        assertThat(details).doesNotContain("wrong-secret-xyz");

        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT outcome, target_type, target_id, details->>'scope' AS scope,"
                        + " details->>'clientAddress' AS client_address"
                        + " FROM audit_log WHERE action = 'LOGIN_THROTTLED' AND username = ?", username);
        assertThat(row).containsEntry("outcome", "FAILURE")
                .containsEntry("target_type", "USER")
                .containsEntry("target_id", username)
                .containsEntry("scope", "LOGIN")
                .containsEntry("client_address", ip);
    }

    @Test
    void throttledRequestsWriteNoLoginAuditRows() throws Exception {
        String ip = peer();
        for (int i = 0; i < 5; i++) {
            login(username, "wrong-secret-xyz", ip);
        }
        Integer before = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'LOGIN' AND username = ?", Integer.class, username);
        for (int i = 0; i < 10; i++) {
            assertThat(login(username, "wrong-secret-xyz", ip).getResponse().getStatus()).isEqualTo(429);
        }
        Integer after = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'LOGIN' AND username = ?", Integer.class, username);
        assertThat(before).isEqualTo(5);
        assertThat(after).isEqualTo(before);
    }

    private static final String NEW_PASSWORD = "A-brand-new-Passw0rd!";

    private MockHttpSession signIn(String ip) throws Exception {
        MvcResult r = login(username, PASSWORD, ip);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private MvcResult changePassword(MockHttpSession session, String current, String ip) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/password").session(session).cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()).contentType(APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, NEW_PASSWORD))
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
        return mvc.perform(request).andReturn();
    }

    /** Fires the calls at the same moment and returns the HTTP statuses. */
    private List<Integer> concurrently(int n, java.util.concurrent.Callable<MvcResult> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call().getResponse().getStatus();
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
    void wrongCurrentPasswordOnPasswordChangeIsThrottled() throws Exception {
        String ip = peer();
        MockHttpSession session = signIn(ip);
        for (int i = 0; i < 5; i++) {
            assertThat(changePassword(session, "wrong-current", ip).getResponse().getStatus()).isEqualTo(400);
        }

        MvcResult sixth = changePassword(session, "wrong-current", ip);

        assertThat(sixth.getResponse().getStatus()).isEqualTo(429);
        assertThat(sixth.getResponse().getHeader("Retry-After")).isEqualTo("900");
        // The correct current password does not help while locked, and nothing changed.
        assertThat(changePassword(session, PASSWORD, ip).getResponse().getStatus()).isEqualTo(429);
        assertThat(login(username, PASSWORD, peer()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void successfulPasswordChangeClearsThePasswordCounter() throws Exception {
        String ip = peer();
        MockHttpSession session = signIn(ip);
        for (int i = 0; i < 4; i++) {
            assertThat(changePassword(session, "wrong-current", ip).getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(changePassword(session, PASSWORD, ip).getResponse().getStatus()).isEqualTo(204);

        MockHttpSession again = (MockHttpSession) login(username, NEW_PASSWORD, ip).getRequest().getSession(false);
        for (int i = 0; i < 5; i++) {
            assertThat(changePassword(again, "wrong-current", ip).getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(changePassword(again, "wrong-current", ip).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void passwordChangeCounterIsSeparateFromLoginCounter() throws Exception {
        String ip = peer();
        MockHttpSession session = signIn(ip);
        for (int i = 0; i < 5; i++) {
            changePassword(session, "wrong-current", ip);
        }

        assertThat(changePassword(session, PASSWORD, ip).getResponse().getStatus()).isEqualTo(429);
        assertThat(login(username, PASSWORD, ip).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void parallelWrongResetCodesAreEvaluatedAtMostMaxAttemptsTimes() throws Exception {
        String ip = peer();
        String body = "{\"username\":\"%s\",\"code\":\"000000\",\"newPassword\":\"A-brand-new-Passw0rd!\"}"
                .formatted(username);

        List<Integer> statuses = concurrently(60, () -> send("/api/auth/password-reset/confirm", body, ip, null));

        assertThat(statuses).filteredOn(st -> st == 400).hasSize(5);
        assertThat(statuses).filteredOn(st -> st == 429).hasSize(55);
    }

    @Test
    void parallelWrongCurrentPasswordsAreEvaluatedAtMostMaxAttemptsTimes() throws Exception {
        String ip = peer();
        MockHttpSession session = signIn(ip);

        List<Integer> statuses = concurrently(60, () -> changePassword(session, "wrong-current", ip));

        assertThat(statuses).filteredOn(st -> st == 400).hasSize(5);
        assertThat(statuses).filteredOn(st -> st == 429).hasSize(55);
    }

    @Test
    void parallelWrongLoginsAreEvaluatedAtMostMaxAttemptsTimes() throws Exception {
        String ip = peer();

        List<Integer> statuses = concurrently(60, () -> login(username, "wrong", ip));

        assertThat(statuses).filteredOn(st -> st == 401).hasSize(5);
        assertThat(statuses).filteredOn(st -> st == 429).hasSize(55);
    }

    @Test
    void policyViolationsWithTheCorrectCurrentPasswordNeverLockTheAccount() throws Exception {
        String ip = peer();
        MockHttpSession session = signIn(ip);
        for (int i = 0; i < 12; i++) {
            MockHttpServletRequestBuilder request = post("/api/auth/password").session(session).cookie(csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue()).contentType(APPLICATION_JSON)
                    .content("{\"currentPassword\":\"%s\",\"newPassword\":\"short\"}".formatted(PASSWORD))
                    .with(r -> {
                        r.setRemoteAddr(ip);
                        return r;
                    });
            assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(400);
        }
    }

    @Test
    void policyViolationsWithTheCorrectResetCodeNeverLockTheAccount() throws Exception {
        String ip = peer();
        String code = "424242";
        String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("""
                INSERT INTO password_reset_codes (user_id, code_hash, expires_at, failed_attempts, created_at)
                SELECT id, ?, ?, 0, ? FROM users WHERE username = ?""", hash,
                java.sql.Timestamp.from(clock.instant().plus(Duration.ofHours(1))),
                java.sql.Timestamp.from(clock.instant()), username);
        String body = "{\"username\":\"%s\",\"code\":\"%s\",\"newPassword\":\"short\"}"
                .formatted(username, code);

        for (int i = 0; i < 12; i++) {
            assertThat(send("/api/auth/password-reset/confirm", body, ip, null).getResponse().getStatus())
                    .isEqualTo(400);
        }
    }
}
