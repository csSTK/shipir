package dev.bethge.shipir.auth.oidc;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.SessionInvalidator;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.auth.dto.UserResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * ADMIN pre-link of an existing account to a provider identity (issuer + {@code sub}), so the user is admitted
 * with auto-provisioning off. One identity per user, one user per identity (also enforced by the database).
 * The sign-in itself still only matches on the exact pair, never on a name or email claim.
 */
@Service
public class ExternalIdentityService {

    private final UserRepository users;
    private final OidcProperties properties;
    private final SessionInvalidator sessions;
    private final OidcSettingsService settings;

    ExternalIdentityService(UserRepository users, OidcProperties properties, SessionInvalidator sessions,
                            OidcSettingsService settings) {
        this.settings = settings;
        this.users = users;
        this.properties = properties;
        this.sessions = sessions;
    }

    @Auditable(action = "USER_IDENTITY_LINK", targetType = "USER", targetId = "#id",
            details = "{'issuer': #issuer, 'subject': #subject}")
    @Transactional
    public UserResponse link(UUID id, String issuer, String subject, String actor) {
        String cleanIssuer = issuer == null ? null : issuer.strip();
        if (!properties.isValidIssuer(cleanIssuer)) {
            throw new OidcException(HttpStatus.BAD_REQUEST, "INVALID_ISSUER_URL",
                    "The issuer must be an https URL without credentials, query or fragment");
        }
        if (!validSubject(subject)) {
            throw new OidcException(HttpStatus.BAD_REQUEST, "INVALID_SUBJECT",
                    "The subject must be 1-255 characters without control characters or surrounding whitespace");
        }
        // With OIDC configured the pair must match what sign-in compares: the configured issuer, stored as saved
        cleanIssuer = settings.activeIssuer().map(active -> {
            if (!normalize(active).equals(normalize(inputIssuer(issuer)))) {
                throw new OidcException(HttpStatus.BAD_REQUEST, "ISSUER_MISMATCH",
                        "The issuer must be the configured provider: " + active);
            }
            return active;
        }).orElse(cleanIssuer);
        User user = users.findById(id).orElseThrow(this::notFound);
        if (user.getUsername().equals(actor)) {
            // also keeps the audit insert (separate transaction, FK to the acting user) from waiting on our own row lock
            throw new OidcException(HttpStatus.BAD_REQUEST, "SELF_LINK",
                    "Another administrator must link your identity");
        }
        if (user.isExternal()) {
            throw new OidcException(HttpStatus.CONFLICT, "IDENTITY_ALREADY_LINKED",
                    "This user is already linked to an identity; unlink it first");
        }
        if (users.findByExternalIssuerAndExternalSubject(cleanIssuer, subject).isPresent()) {
            throw identityTaken();
        }
        user.linkExternalIdentity(cleanIssuer, subject);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw identityTaken();
        }
        return UserResponse.from(user);
    }

    /** Ends the user's sessions too: an identity that was revoked must not stay signed in. */
    @Auditable(action = "USER_IDENTITY_UNLINK", targetType = "USER", targetId = "#id")
    @Transactional
    public UserResponse unlink(UUID id, String actor) {
        User user = users.findById(id).orElseThrow(this::notFound);
        if (user.getUsername().equals(actor)) {
            throw new OidcException(HttpStatus.BAD_REQUEST, "SELF_UNLINK",
                    "You cannot unlink your own identity");
        }
        if (user.isExternal()) {
            user.unlinkExternalIdentity();
            sessions.expireSessionsOf(user.getUsername());
        }
        return UserResponse.from(user);
    }

    private static String inputIssuer(String issuer) {
        return issuer == null ? "" : issuer.strip();
    }

    /** Strip, drop one trailing slash, lower-case scheme and host only (path, port and case of the rest stay). */
    static String normalize(String issuer) {
        String s = issuer.strip();
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        int schemeEnd = s.indexOf("://");
        if (schemeEnd < 0) {
            return s;
        }
        int hostEnd = s.indexOf('/', schemeEnd + 3);
        if (hostEnd < 0) {
            hostEnd = s.length();
        }
        return s.substring(0, hostEnd).toLowerCase(java.util.Locale.ROOT) + s.substring(hostEnd);
    }

    private static boolean validSubject(String subject) {
        if (subject == null || subject.isEmpty() || subject.length() > 255 || !subject.equals(subject.strip())) {
            return false;
        }
        return subject.chars().noneMatch(Character::isISOControl);
    }

    private OidcException notFound() {
        return new OidcException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
    }

    private OidcException identityTaken() {
        return new OidcException(HttpStatus.CONFLICT, "IDENTITY_TAKEN", "This identity is linked to another user");
    }
}
