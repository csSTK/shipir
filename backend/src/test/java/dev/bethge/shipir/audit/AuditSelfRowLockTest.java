package dev.bethge.shipir.audit;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The audit entry is written in its own transaction with a foreign key to the acting user. When the audited
 * (transactional) method has updated an indexed column of the acting user's own row, the caller's transaction holds
 * a row lock that the audit insert's FK check waits on: a self-deadlock. The audit entry must still be written.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, AuditSelfRowLockTest.TestBeans.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuditSelfRowLockTest {

    static class SelfUpdatingService {
        private final UserRepository users;

        SelfUpdatingService(UserRepository users) {
            this.users = users;
        }

        @Auditable(action = "TEST_SELF_UPDATE", targetType = "USER")
        @Transactional
        public void updateOwnIdentity(String actor) {
            User me = users.findByUsername(actor).orElseThrow();
            me.linkExternalIdentity("https://issuer.example", "sub-self-lock");
            users.saveAndFlush(me);
        }

        @Auditable(action = "TEST_ROLLBACK_ONLY", targetType = "USER", details = "{'k': 'v'}")
        @Transactional
        public void markRollbackOnlyAndReturn() {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }

        @Auditable(action = "TEST_NO_TX", targetType = "USER")
        public void withoutTransaction() {
        }

        @Auditable(action = "TEST_SELF_UPDATE_FAILS", targetType = "USER")
        @Transactional
        public void updateOwnIdentityThenFail(String actor) {
            updateOwnIdentity(actor);
            throw new IllegalStateException("boom");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        SelfUpdatingService selfUpdatingService(UserRepository users) {
            return new SelfUpdatingService(users);
        }
    }

    @Autowired
    SelfUpdatingService service;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void clearEntries() {
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'TEST_%'");
    }

    /** The timeout runs the body on another thread, so the signed-in user is set there. */
    private static void asAdmin(Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", "n/a", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("UPDATE users SET external_issuer = NULL, external_subject = NULL WHERE username = 'admin'");
    }

    private Long entries(String action, String outcome) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND outcome = ? "
                + "AND user_id = (SELECT id FROM users WHERE username = 'admin')", Long.class, action, outcome);
    }

    @Test
    void successEntryIsWrittenWhenTheActionLockedTheActorsOwnRow() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> asAdmin(() -> service.updateOwnIdentity("admin")));

        assertThat(entries("TEST_SELF_UPDATE", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void failureEntryIsKeptWhenTheActionLockedTheActorsOwnRowAndRolledBack() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                assertThatThrownBy(() -> asAdmin(() -> service.updateOwnIdentityThenFail("admin")))
                        .isInstanceOf(IllegalStateException.class));

        assertThat(entries("TEST_SELF_UPDATE_FAILS", "FAILURE")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT external_issuer FROM users WHERE username = 'admin'", String.class))
                .isNull();
    }

    private Long entries(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Long.class, action);
    }

    @Test
    void methodThatReturnsNormallyButLeavesTheTransactionRollbackOnlyIsAuditedAsFailure() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> asAdmin(service::markRollbackOnlyAndReturn));

        assertThat(entries("TEST_ROLLBACK_ONLY", "SUCCESS")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'TEST_ROLLBACK_ONLY' "
                + "AND outcome = 'FAILURE' AND user_id IS NULL AND details IS NULL", Long.class)).isEqualTo(1);
    }

    @Test
    void callWithoutTransactionIsWrittenDirectly() {
        asAdmin(service::withoutTransaction);

        assertThat(entries("TEST_NO_TX", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void methodJoinedIntoAnOuterTransactionIsWrittenWithTheOuterOutcome() {
        asAdmin(() -> tx.executeWithoutResult(status -> {
            service.updateOwnIdentity("admin");
            assertThat(entries("TEST_SELF_UPDATE")).as("not before the outer transaction ends").isZero();
        }));
        assertThat(entries("TEST_SELF_UPDATE", "SUCCESS")).isEqualTo(1);

        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'TEST_%'");
        asAdmin(() -> tx.executeWithoutResult(status -> {
            service.updateOwnIdentity("admin");
            status.setRollbackOnly();
        }));
        assertThat(entries("TEST_SELF_UPDATE", "SUCCESS")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'TEST_SELF_UPDATE' "
                + "AND outcome = 'FAILURE'", Long.class)).isEqualTo(1);
    }
}
