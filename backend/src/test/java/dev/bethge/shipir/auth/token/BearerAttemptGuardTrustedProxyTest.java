package dev.bethge.shipir.auth.token;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Bearer throttling behind a trusted proxy: the forwarded client address (not the proxy's) is the one counted by
 * {@link BearerAttemptGuard}. Mirrors {@code LoginThrottleTrustedProxyTest} for the bearer scope.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = "shipir.security.trusted-proxies=127.0.0.0/8")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BearerAttemptGuardTrustedProxyTest {

    /** Default {@code shipir.security.login.max-attempts}: bad bearer values from one address before it is locked. */
    private static final int MAX_ATTEMPTS = 5;
    private static final String CLIENT_A = "198.51.100.7";
    private static final String CLIENT_B = "198.51.100.8";
    private static final String ME = "/api/auth/me";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;

    private String validToken;

    @BeforeEach
    void seedUserAndToken() {
        User user = users.findByUsername("bearer-proxy").orElseGet(() -> users.save(new User("bearer-proxy", "x", Role.OPERATOR)));
        jdbc.update("DELETE FROM api_tokens WHERE user_id = ?", user.getId());
        validToken = TokenCodec.newToken(ApiTokenAuthenticator.SCHEME_PREFIX);
        jdbc.update("INSERT INTO api_tokens (user_id, name, token_hash, prefix, created_at) VALUES (?, 'proxy', ?, ?, ?)",
                user.getId(), TokenCodec.sha256Hex(validToken), validToken.substring(0, 8), Timestamp.from(Instant.now()));
    }

    private MvcResult meWithToken(String token, String forwardedFor) throws Exception {
        MockHttpServletRequestBuilder request = get(ME).header("Authorization", "Bearer " + token);
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mvc.perform(request).andReturn();
    }

    /** A well-formed token that is not in the database: rejected, and counted against the client address. */
    private static String unknownToken() {
        return TokenCodec.newToken(ApiTokenAuthenticator.SCHEME_PREFIX);
    }

    @Test
    void badTokensLockOnlyTheForwardedClientAndValidTokenStillWorks() throws Exception {
        // Client A sends bad tokens through the trusted proxy until its address is locked.
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertThat(meWithToken(unknownToken(), CLIENT_A).getResponse().getStatus()).isEqualTo(401);
        }

        MvcResult locked = meWithToken(unknownToken(), CLIENT_A);
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(locked.getResponse().getHeader("Retry-After")).isNotBlank();
        assertThat(locked.getResponse().getContentAsString()).contains("TOO_MANY_ATTEMPTS");

        // Client B behind the same proxy is not affected by A's lockout.
        assertThat(meWithToken(unknownToken(), CLIENT_B).getResponse().getStatus()).isEqualTo(401);

        // A valid token from the locked client is authenticated first and is never refused.
        assertThat(meWithToken(validToken, CLIENT_A).getResponse().getStatus()).isEqualTo(200);
    }
}
