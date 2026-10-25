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
 * locked out by a few typos of different users. A success clears only the username key, so a valid own
 * account cannot be used to reset the counter of the address.
 */
@Component
public class LoginThrottle {

    /** WEBHOOK: public redeploy webhook, counted per client address on unknown tokens (see {@code DeploymentWebhookService}). */
    public enum Scope { LOGIN, RESET, WEBHOOK }

    private static final int ADDRESS_FACTOR = 4;
    private static final int PURGE_THRESHOLD = 1_000;

    private static final class Entry {
        final Deque<Instant> failures = new ArrayDeque<>();
        Instant lockedUntil;
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

    /** A success clears the username counter. */
    public synchronized void recordSuccess(Scope scope, String username) {
        entries.remove(userKey(scope, username));
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
        entry.failures.addLast(now);
        if (entry.failures.size() >= limit) {
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
