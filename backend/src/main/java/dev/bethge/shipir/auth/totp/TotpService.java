package dev.bethge.shipir.auth.totp;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.auth.totp.dto.SetupResponse;
import dev.bethge.shipir.common.security.SecretCipher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * TOTP enrolment, second-login-step verification, disabling and admin reset. The secret is stored encrypted
 * ({@link SecretCipher}) and only returned by {@link #setup}; recovery codes only as hashes. Wrong codes are
 * throttled per user ({@link TwoFactorAttemptLimiter}); an accepted time step is never accepted again.
 * A password-verified login waits for the second step as a session attribute, not as a security context, so
 * such a session is unauthenticated for every endpoint except {@code /api/auth/2fa/verify} and logout.
 */
@Service
@EnableConfigurationProperties(TwoFactorProperties.class)
public class TotpService {

    static final String PENDING_USER = "shipir.mfa.pending.user";
    static final String PENDING_UNTIL = "shipir.mfa.pending.until";
    private static final String ISSUER = "Shipir";
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int RECOVERY_HALF = 5;

    private final UserRepository users;
    private final UserRecoveryCodeRepository recoveryCodes;
    private final SecuritySettingsService settings;
    private final SecretCipher cipher;
    private final PasswordEncoder encoder;
    private final TwoFactorAttemptLimiter limiter;
    private final TwoFactorProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    TotpService(UserRepository users, UserRecoveryCodeRepository recoveryCodes, SecuritySettingsService settings,
                SecretCipher cipher, PasswordEncoder encoder, TwoFactorAttemptLimiter limiter,
                TwoFactorProperties properties, Clock clock) {
        this.users = users;
        this.recoveryCodes = recoveryCodes;
        this.settings = settings;
        this.cipher = cipher;
        this.encoder = encoder;
        this.limiter = limiter;
        this.properties = properties;
        this.clock = clock;
    }

    // ---- login step ----

    /** Marks the session as "password verified, second factor pending" (new session id, nothing authenticated). */
    public void beginPending(HttpServletRequest request, String username) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        HttpSession session = request.getSession(true);
        // A session that was logged in as someone else must not stay that user while a new login is pending.
        session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        SecurityContextHolder.clearContext();
        session.setAttribute(PENDING_USER, username);
        session.setAttribute(PENDING_UNTIL, clock.instant().plus(properties.pendingTtl()));
    }

    /** The user whose password was verified in this session and who still owes the second factor. */
    public Optional<String> pendingUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object user = session.getAttribute(PENDING_USER);
        Object until = session.getAttribute(PENDING_UNTIL);
        if (user instanceof String name && until instanceof Instant t && clock.instant().isBefore(t)) {
            return Optional.of(name);
        }
        return Optional.empty();
    }

    public Optional<User> findUser(String username) {
        return users.findByUsername(username);
    }

    public void clearPending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(PENDING_USER);
            session.removeAttribute(PENDING_UNTIL);
        }
    }

    /** True if the TOTP or recovery code is valid (and consumed); locked users get TOO_MANY_ATTEMPTS. */
    @Transactional
    public boolean verifyLogin(String username, String code) {
        User user = users.findByUsernameForUpdate(username).orElse(null);
        // Checked under the row lock: concurrent wrong codes cannot slip past the attempt cap.
        requireNotLocked(username);
        boolean ok = user != null && user.isTwoFactorEnabled() && acceptCode(user, code);
        return outcome(username, ok);
    }

    // ---- enrolment ----

    @Transactional
    public SetupResponse setup(String username) {
        User user = lock(username);
        if (user.isTwoFactorEnabled()) {
            throw new TwoFactorException(HttpStatus.CONFLICT, "TWO_FACTOR_ALREADY_ENABLED",
                    "Two-factor authentication is already enabled");
        }
        String secret = Totp.toBase32(Totp.newSecret());
        user.beginTotpEnrolment(cipher.encrypt(secret));
        return new SetupResponse(secret, otpauthUri(username, secret));
    }

    /** Confirms the pending secret with a first code, enables two-factor and returns the recovery codes (once). */
    @Transactional
    public List<String> confirm(String username, String code) {
        User user = lock(username);
        requireNotLocked(username);
        if (user.isTwoFactorEnabled()) {
            throw new TwoFactorException(HttpStatus.CONFLICT, "TWO_FACTOR_ALREADY_ENABLED",
                    "Two-factor authentication is already enabled");
        }
        if (user.getTotpSecretCiphertext() == null) {
            throw new TwoFactorException(HttpStatus.BAD_REQUEST, "NO_SETUP_IN_PROGRESS",
                    "Start the setup first");
        }
        OptionalLong step = matchTotp(user, code);
        if (!outcome(username, step.isPresent())) {
            throw invalidCode();
        }
        user.enableTotp(clock.instant(), step.getAsLong());
        return newRecoveryCodes(user);
    }

    @Transactional
    public void disable(String username, String password, String code) {
        User user = lock(username);
        requireNotLocked(username);
        if (!user.isTwoFactorEnabled()) {
            throw new TwoFactorException(HttpStatus.CONFLICT, "TWO_FACTOR_NOT_ENABLED",
                    "Two-factor authentication is not enabled");
        }
        if (user.getRole() == Role.ADMIN && settings.require2faForAdmins()) {
            throw new TwoFactorException(HttpStatus.CONFLICT, "TWO_FACTOR_REQUIRED",
                    "Two-factor authentication is required for administrators");
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            outcome(username, false);
            throw new TwoFactorException(HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD",
                    "The current password is incorrect");
        }
        if (!outcome(username, acceptCode(user, code))) {
            throw invalidCode();
        }
        reset(user);
    }

    /** Removes the secret and all recovery codes (disable, or an ADMIN reset for a lost device). */
    @Transactional
    public void reset(User user) {
        user.clearTotp();
        recoveryCodes.deleteByUserId(user.getId());
        limiter.reset(user.getUsername());
    }

    // ---- internals ----

    private User lock(String username) {
        return users.findByUsernameForUpdate(username)
                .orElseThrow(() -> new TwoFactorException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                        "Authentication required"));
    }

    private void requireNotLocked(String username) {
        if (limiter.isLocked(username)) {
            throw new TwoFactorException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                    "Too many wrong codes, try again later");
        }
    }

    private boolean outcome(String username, boolean ok) {
        if (ok) {
            limiter.reset(username);
        } else {
            limiter.recordFailure(username);
        }
        return ok;
    }

    private static TwoFactorException invalidCode() {
        return new TwoFactorException(HttpStatus.BAD_REQUEST, "INVALID_CODE", "The code is invalid or has expired");
    }

    private OptionalLong matchTotp(User user, String code) {
        byte[] ciphertext = user.getTotpSecretCiphertext();
        if (ciphertext == null) {
            return OptionalLong.empty();
        }
        byte[] secret = Totp.fromBase32(cipher.decrypt(ciphertext));
        return Totp.match(secret, code.trim(), Totp.stepAt(clock.instant()), user.getTotpLastUsedStep());
    }

    /** A 6-digit code is checked as TOTP, anything else as a recovery code (consumed on success). */
    private boolean acceptCode(User user, String code) {
        String trimmed = code.trim();
        if (trimmed.matches("\\d{" + Totp.DIGITS + "}")) {
            OptionalLong step = matchTotp(user, trimmed);
            step.ifPresent(user::recordTotpStep);
            return step.isPresent();
        }
        String normalized = normalizeRecovery(trimmed);
        if (normalized.length() != RECOVERY_HALF * 2) {
            return false;
        }
        return recoveryCodes.findUnused(user.getId(), hash(normalized))
                .map(c -> {
                    c.markUsed(clock.instant());
                    return true;
                }).orElse(false);
    }

    private List<String> newRecoveryCodes(User user) {
        recoveryCodes.deleteByUserId(user.getId());
        List<String> shown = new ArrayList<>();
        Instant now = clock.instant();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            StringBuilder raw = new StringBuilder();
            for (int j = 0; j < RECOVERY_HALF * 2; j++) {
                raw.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
            }
            recoveryCodes.save(new UserRecoveryCode(user.getId(), hash(raw.toString()), now));
            shown.add(raw.substring(0, RECOVERY_HALF) + "-" + raw.substring(RECOVERY_HALF));
        }
        return shown;
    }

    private static String normalizeRecovery(String code) {
        return code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private static String hash(String normalized) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String otpauthUri(String username, String secret) {
        String label = URLEncoder.encode(username, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + ISSUER + ":" + label + "?secret=" + secret + "&issuer=" + ISSUER
                + "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.PERIOD_SECONDS;
    }
}
