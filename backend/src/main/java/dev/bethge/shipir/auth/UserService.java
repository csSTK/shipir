package dev.bethge.shipir.auth;

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

    UserService(UserRepository users, PasswordEncoder passwordEncoder, PasswordPolicy policy) {
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
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidCurrentPasswordException();
        }
        List<PasswordPolicy.Violation> violations = policy.validate(newPassword, user.getUsername(), currentPassword);
        if (!violations.isEmpty()) {
            throw new PasswordPolicyException(violations);
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword), Instant.now());
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
