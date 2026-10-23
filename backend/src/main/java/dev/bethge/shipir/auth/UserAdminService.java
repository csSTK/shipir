package dev.bethge.shipir.auth;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.dto.OneTimePasswordResponse;
import dev.bethge.shipir.auth.dto.UserResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * ADMIN user management. One-time passwords are only returned to the caller (never logged or audited) and
 * leave {@code passwordChangedAt} null, so the setup gate forces the user to choose their own password.
 */
@Service
class UserAdminService {

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!#$%&*+-=?@^_";
    private static final String ALL = UPPER + LOWER + DIGITS + SYMBOLS;
    private static final int PASSWORD_LENGTH = 24;

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final SessionInvalidator sessions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    UserAdminService(UserRepository users, PasswordEncoder encoder, SessionInvalidator sessions, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<UserResponse> list() {
        return users.findAll().stream()
                .sorted(java.util.Comparator.comparing(User::getUsername, String.CASE_INSENSITIVE_ORDER))
                .map(UserResponse::from).toList();
    }

    @Auditable(action = "USER_CREATE", targetType = "USER", targetId = "#result?.id")
    @Transactional
    OneTimePasswordResponse create(String username, Role role) {
        if (users.findByUsername(username).isPresent()) {
            throw usernameTaken();
        }
        String password = generatePassword();
        User user = new User(username, encoder.encode(password), role);
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw usernameTaken();
        }
        return new OneTimePasswordResponse(user.getId(), user.getUsername(), user.getRole(), password);
    }

    @Auditable(action = "USER_ROLE_CHANGE", targetType = "USER", targetId = "#id")
    @Transactional
    UserResponse changeRole(UUID id, Role role, String actor) {
        User user = find(id);
        if (user.getRole() != role) {
            if (isSelf(user, actor)) {
                throw new UserAdminException(HttpStatus.BAD_REQUEST, "SELF_DEMOTE", "You cannot change your own role");
            }
            if (user.getRole() == Role.ADMIN) {
                requireOtherActiveAdmin(user);
            }
            user.changeRole(role);
            sessions.expireSessionsOf(user.getUsername());
        }
        return UserResponse.from(user);
    }

    @Auditable(action = "USER_DISABLE", targetType = "USER", targetId = "#id")
    @Transactional
    UserResponse disable(UUID id, String actor) {
        User user = find(id);
        if (isSelf(user, actor)) {
            throw new UserAdminException(HttpStatus.BAD_REQUEST, "SELF_DISABLE", "You cannot disable your own account");
        }
        if (!user.isDisabled()) {
            if (user.getRole() == Role.ADMIN) {
                requireOtherActiveAdmin(user);
            }
            user.disable(clock.instant());
            sessions.expireSessionsOf(user.getUsername());
        }
        return UserResponse.from(user);
    }

    @Auditable(action = "USER_ENABLE", targetType = "USER", targetId = "#id")
    @Transactional
    UserResponse enable(UUID id) {
        User user = find(id);
        user.enable();
        return UserResponse.from(user);
    }

    @Auditable(action = "USER_PASSWORD_RESET", targetType = "USER", targetId = "#id")
    @Transactional
    OneTimePasswordResponse resetPassword(UUID id, String actor) {
        User user = find(id);
        if (isSelf(user, actor)) {
            throw new UserAdminException(HttpStatus.BAD_REQUEST, "SELF_RESET_PASSWORD",
                    "Use the account page to change your own password");
        }
        String password = generatePassword();
        user.resetPasswordHash(encoder.encode(password));
        sessions.expireSessionsOf(user.getUsername());
        return new OneTimePasswordResponse(user.getId(), user.getUsername(), user.getRole(), password);
    }

    /**
     * Not transactional on purpose: a foreign-key violation (user owns records) must not leave a broken
     * persistence context behind for the audit aspect, which looks the acting user up afterwards.
     */
    @Auditable(action = "USER_DELETE", targetType = "USER", targetId = "#id")
    void delete(UUID id, String actor) {
        User user = find(id);
        if (isSelf(user, actor)) {
            throw new UserAdminException(HttpStatus.BAD_REQUEST, "SELF_DELETE", "You cannot delete your own account");
        }
        if (user.getRole() == Role.ADMIN && !user.isDisabled()) {
            requireOtherActiveAdmin(user);
        }
        try {
            users.delete(user);
        } catch (DataIntegrityViolationException e) {
            throw new UserAdminException(HttpStatus.CONFLICT, "USER_HAS_HISTORY",
                    "This user owns environments, deployments or other records; disable the account instead");
        }
        sessions.expireSessionsOf(user.getUsername());
    }

    private User find(UUID id) {
        return users.findById(id).orElseThrow(
                () -> new UserAdminException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
    }

    private static boolean isSelf(User user, String actor) {
        return user.getUsername().equals(actor);
    }

    /** Refuses an action that would leave no enabled ADMIN. */
    private void requireOtherActiveAdmin(User target) {
        if (users.countByRoleAndDisabledAtIsNullAndIdNot(Role.ADMIN, target.getId()) == 0) {
            throw new UserAdminException(HttpStatus.CONFLICT, "LAST_ADMIN", "At least one enabled ADMIN must remain");
        }
    }

    private static UserAdminException usernameTaken() {
        return new UserAdminException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "This username is already taken");
    }

    /** Random password with every character class, within the password policy (15-128 characters). */
    private String generatePassword() {
        List<Character> chars = new ArrayList<>();
        for (String set : new String[]{UPPER, LOWER, DIGITS, SYMBOLS}) {
            chars.add(set.charAt(random.nextInt(set.length())));
        }
        while (chars.size() < PASSWORD_LENGTH) {
            chars.add(ALL.charAt(random.nextInt(ALL.length())));
        }
        Collections.shuffle(chars, random);
        StringBuilder sb = new StringBuilder();
        chars.forEach(sb::append);
        return sb.toString();
    }
}
