package dev.bethge.shipir.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit test of the throttle with a fixed clock (5 attempts per username, 20 per address). */
class LoginThrottleTest {

    private static final LoginThrottle.Scope SCOPE = LoginThrottle.Scope.LOGIN;

    private final LoginThrottle throttle = new LoginThrottle(
            Clock.fixed(Instant.parse("2030-01-01T10:00:00Z"), ZoneOffset.UTC),
            new AuthThrottleProperties(
                    new AuthThrottleProperties.Login(5, Duration.ofMinutes(15), Duration.ofMinutes(15)), null));

    private void admitFour() {
        for (int i = 0; i < 4; i++) {
            assertThat(throttle.admit(SCOPE, "alice", "10.0.0.1").admitted()).isTrue();
        }
    }

    @Test
    void fifthAdmittedAttemptStartsTheLockoutAndTheSixthIsRefused() {
        admitFour();

        LoginThrottle.Admission fifth = throttle.admit(SCOPE, "alice", "10.0.0.1");

        assertThat(fifth.admitted()).isTrue();
        assertThat(fifth.lockoutStarted()).isTrue();
        assertThat(throttle.admit(SCOPE, "alice", "10.0.0.1").admitted()).isFalse();
    }

    @Test
    void successOfTheAttemptThatStartedTheLockoutUndoesTheLockout() {
        admitFour();
        assertThat(throttle.admit(SCOPE, "alice", "10.0.0.1").lockoutStarted()).isTrue();

        throttle.recordSuccess(SCOPE, "alice", "10.0.0.1");

        assertThat(throttle.lockedFor(SCOPE, "alice", "10.0.0.1")).isEmpty();
        assertThat(throttle.admit(SCOPE, "alice", "10.0.0.1").admitted()).isTrue();
    }

    @Test
    void releaseOfTheAttemptThatStartedTheLockoutUndoesItAndKeepsTheEarlierFailures() {
        admitFour();
        assertThat(throttle.admit(SCOPE, "alice", "10.0.0.1").lockoutStarted()).isTrue();

        throttle.release(SCOPE, "alice", "10.0.0.1");

        assertThat(throttle.lockedFor(SCOPE, "alice", "10.0.0.1")).isEmpty();
        // The four earlier failures are still counted: the next admitted attempt is the fifth again.
        assertThat(throttle.admit(SCOPE, "alice", "10.0.0.1").lockoutStarted()).isTrue();
    }

    @Test
    void releaseOfAnOrdinaryAttemptGivesTheSlotBack() {
        throttle.admit(SCOPE, "alice", "10.0.0.1");
        throttle.release(SCOPE, "alice", "10.0.0.1");
        for (int i = 0; i < 4; i++) {
            throttle.admit(SCOPE, "alice", "10.0.0.1");
        }

        assertThat(throttle.lockedFor(SCOPE, "alice", "10.0.0.1")).isEmpty();
    }

    @Test
    void successOfOneUserDoesNotClearTheAddressCountOfOthers() {
        for (int i = 0; i < 3; i++) {
            throttle.admit(SCOPE, "bob" + i, "10.0.0.1");
        }
        throttle.admit(SCOPE, "alice", "10.0.0.1");
        throttle.recordSuccess(SCOPE, "alice", "10.0.0.1");

        // 3 failures of others remain on the address: 17 more reach the address limit of 20.
        LoginThrottle.Admission last = null;
        for (int i = 0; i < 17; i++) {
            last = throttle.admit(SCOPE, "carol" + i, "10.0.0.1");
        }
        assertThat(last.lockoutStarted()).isTrue();
    }

    @Test
    void usernamesDifferingOnlyByCaseOrWhitespaceShareTheLimit() {
        String[] spellings = {"alice", "Alice", "ALICE", " aLiCe ", "alice"};
        LoginThrottle.Admission last = null;
        for (int i = 0; i < 5; i++) {
            last = throttle.admit(SCOPE, spellings[i], "10.0.0." + i);
        }
        assertThat(last.lockoutStarted()).isTrue();
        assertThat(throttle.admit(SCOPE, "aLICE", "10.0.0.9").admitted()).isFalse();
    }

    @Test
    void scopesAreCountedSeparately() {
        admitFour();

        assertThat(throttle.admit(LoginThrottle.Scope.PASSWORD, "alice", "10.0.0.1").lockoutStarted()).isFalse();
    }

    @Test
    void addressOnlyAdmissionCountsTheAddressAndLeavesTheUsernameKeysAlone() {
        LoginThrottle.Scope scope = LoginThrottle.Scope.OIDC_AUTHORIZE;
        // 20 per address (4 x 5), not 5: one address may start several sign-ins.
        for (int i = 0; i < 19; i++) {
            assertThat(throttle.admitAddress(scope, "10.0.0.1").admitted()).isTrue();
        }
        LoginThrottle.Admission last = throttle.admitAddress(scope, "10.0.0.1");
        assertThat(last.admitted()).isTrue();
        assertThat(last.lockoutStarted()).isTrue();

        assertThat(throttle.admitAddress(scope, "10.0.0.1").admitted()).isFalse();
        assertThat(throttle.admitAddress(scope, "10.0.0.1").lockedFor()).isPresent();
        assertThat(throttle.admitAddress(scope, "10.0.0.2").admitted()).isTrue();
        assertThat(throttle.admit(LoginThrottle.Scope.LOGIN, "alice", "10.0.0.1").admitted()).isTrue();
    }

    @Test
    void mapStaysWithinTheCapAfterManyDistinctUsernames() {
        LoginThrottle capped = capped(50);
        for (int i = 0; i < 500; i++) {
            capped.recordFailure(SCOPE, "user" + i, "10.0.0.1");
        }

        assertThat(capped.size()).isLessThanOrEqualTo(50);
    }

    @Test
    void lockedEntryIsNeverEvictedBeforeItsLockExpires() {
        LoginThrottle capped = capped(50);
        for (int i = 0; i < 5; i++) {
            capped.admit(SCOPE, "victim", "10.9.9.9");
        }
        assertThat(capped.lockedFor(SCOPE, "victim", "10.9.9.9")).isPresent();

        for (int i = 0; i < 500; i++) {
            capped.recordFailure(SCOPE, "user" + i, "10.0.0.1");
        }

        assertThat(capped.lockedFor(SCOPE, "victim", "10.9.9.9")).isPresent();
    }

    @Test
    void scheduledPurgeDropsExpiredEntries() {
        java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2030-01-01T10:00:00Z"));
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now.get(); }
        };
        LoginThrottle t = new LoginThrottle(clock, new AuthThrottleProperties(
                new AuthThrottleProperties.Login(5, Duration.ofMinutes(15), Duration.ofMinutes(15)), null), 50);
        t.recordFailure(SCOPE, "alice", "10.0.0.1");
        assertThat(t.size()).isEqualTo(2);

        now.set(now.get().plus(Duration.ofHours(1)));
        t.purgeExpired();

        assertThat(t.size()).isZero();
    }

    private static LoginThrottle capped(int max) {
        return new LoginThrottle(Clock.fixed(Instant.parse("2030-01-01T10:00:00Z"), ZoneOffset.UTC),
                new AuthThrottleProperties(
                        new AuthThrottleProperties.Login(5, Duration.ofMinutes(15), Duration.ofMinutes(15)), null), max);
    }
}
