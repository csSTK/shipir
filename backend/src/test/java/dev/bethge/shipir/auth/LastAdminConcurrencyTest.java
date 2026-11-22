package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two ADMINs removing each other must not both succeed. The first action is held open (uncommitted) while the
 * second one starts: it has to wait for the first to commit and then see the first ADMIN as gone.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LastAdminConcurrencyTest {

    @Autowired
    private UserAdminService admin;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private UUID a;
    private UUID b;

    @BeforeEach
    void twoAdmins() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("DELETE FROM users WHERE username <> 'admin'");
        jdbc.update("UPDATE users SET role = 'ADMIN', disabled_at = NULL WHERE username = 'admin'");
        a = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", UUID.class);
        b = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, username, password_hash, role) VALUES (?, 'second', 'x', 'ADMIN')", b);
    }

    @Test
    void parallelDemotionsLeaveOneAdmin() throws Exception {
        assertSecondIsRefused(() -> admin.changeRole(a, Role.VIEWER, "nobody"),
                () -> admin.changeRole(b, Role.VIEWER, "nobody"));
    }

    @Test
    void parallelDisablesLeaveOneAdmin() throws Exception {
        assertSecondIsRefused(() -> admin.disable(a, "nobody"), () -> admin.disable(b, "nobody"));
    }

    @Test
    void parallelDeletesLeaveOneAdmin() throws Exception {
        assertSecondIsRefused(() -> admin.delete(a, "nobody"), () -> admin.delete(b, "nobody"));
    }

    private void assertSecondIsRefused(Runnable first, Runnable second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            first.run();
            firstDone.countDown();
            await(release);
        }));
        assertThat(firstDone.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> other = CompletableFuture.runAsync(second);
        Thread.sleep(500);
        assertThat(other).as("second action waits for the first to commit").isNotDone();
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> other.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(UserAdminException.class).hasRootCauseMessage("At least one enabled ADMIN must remain");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE role = 'ADMIN' AND disabled_at IS NULL", Integer.class)).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
