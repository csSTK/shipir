package dev.bethge.shipir.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Self-service password reset with a short-lived one-time code. Never logs codes or passwords. */
@Service
@EnableConfigurationProperties(PasswordResetProperties.class)
class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordResetCodeRepository codes;
    private final PasswordResetCodeSender sender;
    private final PasswordResetProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy policy;
    private final Clock clock;
    private final SessionInvalidator sessions;
    private final dev.bethge.shipir.auth.token.ApiTokenService apiTokens;

    PasswordResetService(UserRepository users, PasswordResetCodeRepository codes, PasswordResetCodeSender sender,
                         PasswordResetProperties properties, PasswordEncoder passwordEncoder,
                         PasswordPolicy policy, Clock clock, SessionInvalidator sessions,
                         dev.bethge.shipir.auth.token.ApiTokenService apiTokens) {
        this.apiTokens = apiTokens;
        this.users = users;
        this.codes = codes;
        this.sender = sender;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.policy = policy;
        this.clock = clock;
        this.sessions = sessions;
    }

    /** Unknown usernames and SSO users (no local password) are a silent no-op. Earlier open codes of the user become invalid. */
    @Transactional
    void requestReset(String username) {
        Optional<User> found = users.findByUsername(username);
        if (found.isEmpty() || found.get().isExternal()) {
            return;
        }
        User user = found.get();
        Instant now = clock.instant();
        codes.invalidateOpenCodes(user.getId(), now);
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        codes.save(new PasswordResetCode(user.getId(), sha256Hex(code), now.plus(properties.codeTtl()), now));
        sender.send(user, code);
    }

    /**
     * @throws InvalidResetCodeException              unknown user, wrong, expired, used or exhausted code
     * @throws UserService.PasswordPolicyException    new password breaks a rule (the code is not consumed)
     */
    @Transactional(noRollbackFor = InvalidResetCodeException.class)
    void confirmReset(String username, String code, String newPassword) {
        User user = users.findByUsername(username).orElseThrow(InvalidResetCodeException::new);
        if (user.isExternal()) {
            throw new InvalidResetCodeException();
        }
        Instant now = clock.instant();
        PasswordResetCode open = codes.findLatestOpen(user.getId(), now, properties.maxAttempts())
                .orElseThrow(InvalidResetCodeException::new);
        boolean matches = MessageDigest.isEqual(
                sha256Hex(code).getBytes(StandardCharsets.UTF_8), open.getCodeHash().getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            open.recordFailedAttempt();
            throw new InvalidResetCodeException();
        }
        List<PasswordPolicy.Violation> violations = new ArrayList<>(
                policy.validate(newPassword, user.getUsername(), null));
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            violations.add(PasswordPolicy.Violation.SAME_AS_CURRENT);
        }
        if (!violations.isEmpty()) {
            throw new UserService.PasswordPolicyException(violations);
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword), now);
        open.markUsed(now);
        // The reset may be recovery from a compromise: every existing session of the user ends.
        sessions.expireSessionsOf(user.getUsername());
        apiTokens.revokeAllOf(user.getId());
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
