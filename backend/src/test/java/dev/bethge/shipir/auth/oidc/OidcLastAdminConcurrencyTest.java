package dev.bethge.shipir.auth.oidc;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Two SSO ADMINs that both lost the admin group sign in at once: one of them must keep ADMIN. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class OidcLastAdminConcurrencyTest {

    private static final String ISSUER = "https://idp.example.test";

    @Autowired
    private OidcUserResolver resolver;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private final OidcConfig config = new OidcConfig(ISSUER, "client", "secret", "openid", "groups",
            Map.of(Role.VIEWER, List.of("viewers")), false);

    @BeforeEach
    void twoSsoAdmins() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("DELETE FROM users WHERE username <> 'admin'");
        jdbc.update("UPDATE users SET disabled_at = now() WHERE username = 'admin'");
        for (String n : List.of("sso-a", "sso-b")) {
            jdbc.update("INSERT INTO users (username, password_hash, role, external_issuer, external_subject) "
                    + "VALUES (?, 'x', 'ADMIN', ?, ?)", n, ISSUER, n);
        }
    }

    @Test
    void parallelDemotionsKeepOneAdmin() throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            resolver.resolve(config, "sso-a", "sso-a", List.of("viewers"));
            firstDone.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(firstDone.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<?> other = CompletableFuture.supplyAsync(
                () -> resolver.resolve(config, "sso-b", "sso-b", List.of("viewers")));
        Thread.sleep(500);
        assertThat(other).as("second sign-in waits for the first to commit").isNotDone();
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        other.get(10, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE username = 'sso-a'", String.class)).isEqualTo("VIEWER");
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE username = 'sso-b'", String.class)).isEqualTo("ADMIN");
    }
}
