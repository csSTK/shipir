package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Throttle on login and password reset (default settings: 5 attempts, 15 min window and lockout). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, LoginThrottleApiTest.TestBeans.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginThrottleApiTest {

    private static final String PASSWORD = "Old-Passw0rd-for-tests!";

    static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2030-01-01T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
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
    private int ipCounter;

    @BeforeEach
    void setUp() throws Exception {
        username = "user-" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new User(username, encoder.encode(PASSWORD), Role.OPERATOR));
        csrf = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        ipCounter++;
    }

    /** Each test works from its own peer address so tests do not share the address counter. */
    private String peer() {
        return "10.1." + (System.identityHashCode(this) % 200) + "." + ipCounter;
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
    }
}
