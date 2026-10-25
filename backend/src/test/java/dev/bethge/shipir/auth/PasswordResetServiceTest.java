package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest
@Import({PostgresTestConfiguration.class, PasswordResetServiceTest.TestBeans.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetServiceTest {

    private static final String OLD_PASSWORD = "Old-Passw0rd-for-tests!";
    private static final String NEW_PASSWORD = "A-brand-new-Passw0rd!";

    static class CapturingSender implements PasswordResetCodeSender {
        final List<String> codes = new ArrayList<>();

        @Override
        public void send(User user, String code) {
            codes.add(code);
        }

        String last() {
            return codes.get(codes.size() - 1);
        }
    }

    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
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
        CapturingSender capturingSender() {
            return new CapturingSender();
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    private PasswordResetService service;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CapturingSender sender;
    @Autowired
    private MutableClock clock;
    @Autowired
    private org.springframework.security.core.session.SessionRegistry sessionRegistry;

    private String username;

    @BeforeEach
    void createUser() {
        username = "user-" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new User(username, encoder.encode(OLD_PASSWORD), Role.OPERATOR));
        sender.codes.clear();
    }

    private User user() {
        return users.findByUsername(username).orElseThrow();
    }

    private String requestCode() {
        service.requestReset(username);
        return sender.last();
    }

    private String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    private String createExternalUser() {
        String name = "sso-" + UUID.randomUUID().toString().substring(0, 8);
        users.save(User.external(name, encoder.encode(OLD_PASSWORD), Role.OPERATOR,
                "https://idp.example", "sub-" + name, clock.instant()));
        return name;
    }

    @Test
    void requestResetForExternalUserSendsNoCodeAndStoresNone() {
        String name = createExternalUser();

        service.requestReset(name);

        assertThat(sender.codes).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM password_reset_codes c JOIN users u ON u.id = c.user_id WHERE u.username = ?",
                Integer.class, name)).isZero();
    }

    @Test
    void confirmResetForExternalUserIsRefusedAndKeepsHash() {
        String name = createExternalUser();
        String hashBefore = users.findByUsername(name).orElseThrow().getPasswordHash();

        assertThatThrownBy(() -> service.confirmReset(name, "123456", NEW_PASSWORD))
                .isInstanceOf(InvalidResetCodeException.class);
        assertThat(users.findByUsername(name).orElseThrow().getPasswordHash()).isEqualTo(hashBefore);
    }

    @Test
    void confirmResetRefusesExternalUserEvenWithAValidOpenCode() {
        String name = createExternalUser();
        User external = users.findByUsername(name).orElseThrow();
        // a code that existed before the account was linked to SSO
        jdbc.update("INSERT INTO password_reset_codes (user_id, code_hash, expires_at, created_at) "
                        + "VALUES (?, encode(sha256('123456'::bytea), 'hex'), ?, ?)",
                external.getId(), java.sql.Timestamp.from(clock.instant().plusSeconds(60)),
                java.sql.Timestamp.from(clock.instant()));

        assertThatThrownBy(() -> service.confirmReset(name, "123456", NEW_PASSWORD))
                .isInstanceOf(InvalidResetCodeException.class);
        assertThat(encoder.matches(NEW_PASSWORD, users.findByUsername(name).orElseThrow().getPasswordHash()))
                .isFalse();
    }

    @Test
    void validCodeResetsPasswordAndSetsPasswordChangedAt() {
        assertThat(user().isSetupRequired()).isTrue();
        String code = requestCode();

        service.confirmReset(username, code, NEW_PASSWORD);

        assertThat(encoder.matches(NEW_PASSWORD, user().getPasswordHash())).isTrue();
        assertThat(user().getPasswordChangedAt()).isEqualTo(clock.instant());
        assertThat(user().isSetupRequired()).isFalse();
    }

    @Test
    void codeIsSingleUse() {
        String code = requestCode();
        service.confirmReset(username, code, NEW_PASSWORD);

        assertThatThrownBy(() -> service.confirmReset(username, code, "Another-Passw0rd-123!"))
                .isInstanceOf(InvalidResetCodeException.class);
    }

    @Test
    void codeExpiresAfterTtl() {
        String code = requestCode();
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThatThrownBy(() -> service.confirmReset(username, code, NEW_PASSWORD))
                .isInstanceOf(InvalidResetCodeException.class);
    }

    @Test
    void codeIsStillValidJustBeforeTtl() {
        String code = requestCode();
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));

        service.confirmReset(username, code, NEW_PASSWORD);
        assertThat(encoder.matches(NEW_PASSWORD, user().getPasswordHash())).isTrue();
    }

    @Test
    void correctCodeFailsAfterFiveWrongAttempts() {
        String code = requestCode();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.confirmReset(username, wrong(code), NEW_PASSWORD))
                    .isInstanceOf(InvalidResetCodeException.class);
        }

        assertThatThrownBy(() -> service.confirmReset(username, code, NEW_PASSWORD))
                .isInstanceOf(InvalidResetCodeException.class);
        assertThat(encoder.matches(OLD_PASSWORD, user().getPasswordHash())).isTrue();
    }

    @Test
    void newRequestInvalidatesPreviousCode() {
        String first = requestCode();
        String second = requestCode();

        if (!first.equals(second)) {
            assertThatThrownBy(() -> service.confirmReset(username, first, NEW_PASSWORD))
                    .isInstanceOf(InvalidResetCodeException.class);
        }
        Integer open = jdbc.queryForObject("""
                SELECT count(*) FROM password_reset_codes c JOIN users u ON u.id = c.user_id
                WHERE u.username = ? AND c.used_at IS NULL""", Integer.class, username);
        assertThat(open).isEqualTo(1);
        service.confirmReset(username, second, NEW_PASSWORD);
    }

    @Test
    void unknownUsernameRequestIsSilentNoOp() {
        service.requestReset("nobody-" + UUID.randomUUID());

        assertThat(sender.codes).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_codes WHERE user_id NOT IN "
                + "(SELECT id FROM users)", Integer.class)).isZero();
    }

    @Test
    void unknownUsernameConfirmThrowsSameExceptionAsWrongCode() {
        String code = requestCode();
        Throwable wrongCode = catchThrowable(
                () -> service.confirmReset(username, wrong(code), NEW_PASSWORD));
        Throwable unknownUser = catchThrowable(
                () -> service.confirmReset("nobody-" + UUID.randomUUID(), code, NEW_PASSWORD));

        assertThat(unknownUser).isInstanceOf(InvalidResetCodeException.class);
        assertThat(wrongCode).isInstanceOf(InvalidResetCodeException.class)
                .hasMessage(unknownUser.getMessage());
    }

    @Test
    void policyViolationsAreReportedAndCodeIsNotConsumed() {
        String code = requestCode();

        assertThatThrownBy(() -> service.confirmReset(username, code, "short"))
                .isInstanceOfSatisfying(UserService.PasswordPolicyException.class,
                        e -> assertThat(e.violations()).contains(PasswordPolicy.Violation.TOO_SHORT));
        assertThatThrownBy(() -> service.confirmReset(username, code, OLD_PASSWORD))
                .isInstanceOfSatisfying(UserService.PasswordPolicyException.class,
                        e -> assertThat(e.violations()).contains(PasswordPolicy.Violation.SAME_AS_CURRENT));

        service.confirmReset(username, code, NEW_PASSWORD);
        assertThat(encoder.matches(NEW_PASSWORD, user().getPasswordHash())).isTrue();
    }

    @Test
    void storesOnlySha256HexHashNeverTheCode() {
        String code = requestCode();

        List<String> hashes = jdbc.queryForList("""
                SELECT c.code_hash FROM password_reset_codes c JOIN users u ON u.id = c.user_id
                WHERE u.username = ?""", String.class, username);
        assertThat(hashes).hasSize(1);
        assertThat(hashes.get(0)).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(code);
        assertThat(code).matches("\\d{6}");
    }

    @Test
    void codeNeverAppearsInLogs(CapturedOutput output) {
        String code = requestCode();
        service.confirmReset(username, code, NEW_PASSWORD);

        assertThat(output.getAll()).doesNotContain(code);
    }

    /** Runs the task on {@code n} threads released at the same moment; returns how many threw nothing. */
    private int runConcurrently(int n, java.util.function.Consumer<Integer> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        task.accept(idx);
                        succeeded.incrementAndGet();
                    } catch (InvalidResetCodeException expected) {
                        // counted as failure
                    }
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        return succeeded.get();
    }

    @Test
    void concurrentWrongGuessesCannotExceedMaxAttempts() throws Exception {
        String code = requestCode();
        String wrong = wrong(code);

        runConcurrently(20, i -> service.confirmReset(username, wrong, NEW_PASSWORD));

        Integer attempts = jdbc.queryForObject("""
                SELECT c.failed_attempts FROM password_reset_codes c JOIN users u ON u.id = c.user_id
                WHERE u.username = ?""", Integer.class, username);
        assertThat(attempts).isEqualTo(5);
        assertThatThrownBy(() -> service.confirmReset(username, code, NEW_PASSWORD))
                .isInstanceOf(InvalidResetCodeException.class);
    }

    @Test
    void concurrentCorrectConfirmsSucceedExactlyOnce() throws Exception {
        String code = requestCode();

        int succeeded = runConcurrently(10, i -> service.confirmReset(username, code, NEW_PASSWORD));

        assertThat(succeeded).isEqualTo(1);
    }

    @Test
    void confirmResetExpiresAllSessionsOfTheUserOnly() {
        var principal = org.springframework.security.core.userdetails.User.withUsername(username)
                .password("x").roles("OPERATOR").build();
        var other = org.springframework.security.core.userdetails.User.withUsername("other-" + username)
                .password("x").roles("OPERATOR").build();
        sessionRegistry.registerNewSession("sess-1-" + username, principal);
        sessionRegistry.registerNewSession("sess-2-" + username, principal);
        sessionRegistry.registerNewSession("sess-o-" + username, other);
        String code = requestCode();

        service.confirmReset(username, code, NEW_PASSWORD);

        assertThat(sessionRegistry.getSessionInformation("sess-1-" + username).isExpired()).isTrue();
        assertThat(sessionRegistry.getSessionInformation("sess-2-" + username).isExpired()).isTrue();
        assertThat(sessionRegistry.getSessionInformation("sess-o-" + username).isExpired()).isFalse();
    }

    @Test
    void failedResetLeavesSessionsAlone() {
        var principal = org.springframework.security.core.userdetails.User.withUsername(username)
                .password("x").roles("OPERATOR").build();
        sessionRegistry.registerNewSession("sess-f-" + username, principal);
        String code = requestCode();

        assertThatThrownBy(() -> service.confirmReset(username, wrong(code), NEW_PASSWORD))
                .isInstanceOf(InvalidResetCodeException.class);

        assertThat(sessionRegistry.getSessionInformation("sess-f-" + username).isExpired()).isFalse();
    }
}
