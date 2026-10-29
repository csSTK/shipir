package dev.bethge.shipir.auth;

import dev.bethge.shipir.auth.token.ApiTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** User account operations. Never logs or echoes passwords. */
@Service
class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy policy;
    private final ApiTokenService apiTokens;

    UserService(UserRepository users, PasswordEncoder passwordEncoder, PasswordPolicy policy, ApiTokenService apiTokens) {
        this.apiTokens = apiTokens;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.policy = policy;
    }

    /**
     * Verifies the current password, applies the policy and stores the new hash.
     *
     * @throws InvalidCurrentPasswordException if the current password does not match (checked first)
     * @throws PasswordPolicyException         if the new password breaks any rule
     */
    @Transactional
    void changePassword(String username, String currentPassword, String newPassword) {
        User user = users.findByUsername(username).orElseThrow(InvalidCurrentPasswordException::new);
        if (user.isExternal()) {
            // SSO users have no local password; refuse so none can be set while local login is enabled
            throw new InvalidCurrentPasswordException();
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidCurrentPasswordException();
        }
        List<PasswordPolicy.Violation> violations = policy.validate(newPassword, user.getUsername(), currentPassword);
        if (!violations.isEmpty()) {
            throw new PasswordPolicyException(violations);
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword), Instant.now());
        // Tokens survive neither a changed nor a stolen-and-changed password: the user mints new ones in the session.
        apiTokens.revokeAllOf(user.getId());
    }

    /** Stores the user's UI language (already validated by the request); null clears it. */
    @Transactional
    void changeLocale(String username, String locale) {
        users.findByUsername(username).ifPresent(user -> user.changeLocale(locale));
    }

    static final class InvalidCurrentPasswordException extends RuntimeException {
        InvalidCurrentPasswordException() {
            super("Current password is incorrect", null, false, false);
        }
    }

    static final class PasswordPolicyException extends RuntimeException {
        private final transient List<PasswordPolicy.Violation> violations;

        PasswordPolicyException(List<PasswordPolicy.Violation> violations) {
            super("Password does not meet the policy", null, false, false);
            this.violations = List.copyOf(violations);
        }

        List<PasswordPolicy.Violation> violations() {
            return violations;
        }
    }
}
