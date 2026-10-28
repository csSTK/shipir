package dev.bethge.shipir.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory brute-force protection for login and password reset, counted per username and per client
 * address (sliding window). Reaching the limit locks that key for the lockout period; further attempts get
 * 429 without being evaluated. State is lost on restart and not shared between instances.
 * The address limit is {@value #ADDRESS_FACTOR} times the username limit so one shared address (NAT) is not
 * locked out by a few typos of different users. {@link #admit} counts an attempt at once, atomically with the
 * lock check, so parallel guesses cannot all pass the check. A success clears the username key and only takes
 * back its own slot on the address key (undoing a lockout that this attempt started), so a valid own account
 * cannot be used to reset the counter of the address.
 */
@Component
public class LoginThrottle {

    /** PASSWORD: password change of a signed-in user (guards the current-password check). WEBHOOK: public redeploy webhook, counted per client address on unknown tokens (see {@code DeploymentWebhookService}). BEARER: failed {@code Authorization: Bearer} credentials, counted per client address (see {@code BearerAttemptGuard}). OIDC_AUTHORIZE: every start of the public single sign-on flow, counted per client address only via {@link #admitAddress}. */
    public enum Scope { LOGIN, RESET, WEBHOOK, PASSWORD, BEARER, OIDC_AUTHORIZE }

    /**
     * Result of {@link #admit}: {@code lockedFor} is set if the attempt was refused; {@code lockoutStarted} if
     * admitting it used up the last allowed attempt and started a lockout.
     */
    public record Admission(Optional<Duration> lockedFor, boolean lockoutStarted) {
        public boolean admitted() {
            return lockedFor.isEmpty();
        }
    }

    private static final int ADDRESS_FACTOR = 4;
    private static final int PURGE_THRESHOLD = 1_000;

    private static final class Entry {
        final Deque<Instant> failures = new ArrayDeque<>();
        Instant lockedUntil;
        /** Failures before the lockout started, so a provisional attempt that started it can be taken back. */
        Deque<Instant> beforeLock;
    }

    private final Clock clock;
    private final AuthThrottleProperties.Login settings;
    private final Map<String, Entry> entries = new HashMap<>();

    LoginThrottle(Clock clock, AuthThrottleProperties properties) {
        this.clock = clock;
        this.settings = properties.login();
    }

    /** Remaining lock time if the username or the address is locked, else empty. */
    public synchronized Optional<Duration> lockedFor(Scope scope, String username, String address) {
        Instant now = clock.instant();
        Duration longest = null;
        for (String key : new String[]{userKey(scope, username), addressKey(scope, address)}) {
            Entry entry = entries.get(key);
            if (entry != null && entry.lockedUntil != null && entry.lockedUntil.isAfter(now)) {
                Duration left = Duration.between(now, entry.lockedUntil);
                if (longest == null || left.compareTo(longest) > 0) {
                    longest = left;
                }
            }
        }
        return Optional.ofNullable(longest);
    }

    /**
     * Atomic check-and-count for an attempt whose outcome is not known yet: if neither key is locked, the attempt
     * is counted as a (provisional) failure under the same lock, so a burst of parallel attempts cannot all pass
     * the check before any is counted. The caller must then call {@link #recordSuccess} or {@link #release} if
     * the attempt turns out to be legitimate; a failed attempt needs no further call.
     */
    public synchronized Admission admit(Scope scope, String username, String address) {
        Optional<Duration> locked = lockedFor(scope, username, address);
        if (locked.isPresent()) {
            return new Admission(locked, false);
        }
        return new Admission(Optional.empty(), recordFailure(scope, username, address));
    }

    /**
     * Like {@link #admit} for requests that have no username (public endpoints): only the address key is checked and
     * counted, with the address limit ({@value #ADDRESS_FACTOR} times the username limit). Every admitted request
     * counts; nothing is taken back.
     */
    public synchronized Admission admitAddress(Scope scope, String address) {
        String key = addressKey(scope, address);
        Instant now = clock.instant();
        Entry entry = entries.get(key);
        if (entry != null && entry.lockedUntil != null && entry.lockedUntil.isAfter(now)) {
            return new Admission(Optional.of(Duration.between(now, entry.lockedUntil)), false);
        }
        if (entries.size() > PURGE_THRESHOLD) {
            purge(now);
        }
        return new Admission(Optional.empty(), fail(key, settings.maxAttempts() * ADDRESS_FACTOR, now));
    }

    /** Takes back the provisional failure of an admitted attempt that was neither a success nor a failure. */
    public synchronized void release(Scope scope, String username, String address) {
        refund(userKey(scope, username));
        refund(addressKey(scope, address));
    }

    /** Counts one failed attempt. Returns true if this attempt started a lockout. */
    public synchronized boolean recordFailure(Scope scope, String username, String address) {
        Instant now = clock.instant();
        if (entries.size() > PURGE_THRESHOLD) {
            purge(now);
        }
        boolean user = fail(userKey(scope, username), settings.maxAttempts(), now);
        boolean addr = fail(addressKey(scope, address), settings.maxAttempts() * ADDRESS_FACTOR, now);
        return user || addr;
    }

    /** A success of an admitted attempt: clears the username counter and takes back its provisional failure on the address. */
    public synchronized void recordSuccess(Scope scope, String username, String address) {
        entries.remove(userKey(scope, username));
        refund(addressKey(scope, address));
    }

    private void refund(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return;
        }
        if (entry.beforeLock != null) {
            // The attempt being taken back started the lockout: undo it.
            entry.beforeLock.removeLast();
            entry.failures.addAll(entry.beforeLock);
            entry.beforeLock = null;
            entry.lockedUntil = null;
        } else if (!entry.failures.isEmpty()) {
            entry.failures.removeLast();
        }
    }

    private boolean fail(String key, int limit, Instant now) {
        Entry entry = entries.computeIfAbsent(key, k -> new Entry());
        if (entry.lockedUntil != null && entry.lockedUntil.isAfter(now)) {
            return false;
        }
        Instant cutoff = now.minus(settings.window());
        while (!entry.failures.isEmpty() && !entry.failures.peekFirst().isAfter(cutoff)) {
            entry.failures.removeFirst();
        }
        entry.beforeLock = null;
        entry.failures.addLast(now);
        if (entry.failures.size() >= limit) {
            entry.beforeLock = new ArrayDeque<>(entry.failures);
            entry.failures.clear();
            entry.lockedUntil = now.plus(settings.lockout());
            return true;
        }
        return false;
    }

    private void purge(Instant now) {
        Instant cutoff = now.minus(settings.window());
        entries.values().removeIf(e ->
                (e.lockedUntil == null || !e.lockedUntil.isAfter(now))
                        && (e.failures.isEmpty() || !e.failures.peekLast().isAfter(cutoff)));
    }

    private static String userKey(Scope scope, String username) {
        return scope + "|u|" + (username == null ? "" : username.strip().toLowerCase(Locale.ROOT));
    }

    private static String addressKey(Scope scope, String address) {
        return scope + "|a|" + address;
    }
}
