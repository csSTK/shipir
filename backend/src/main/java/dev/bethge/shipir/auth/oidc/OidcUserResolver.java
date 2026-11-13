package dev.bethge.shipir.auth.oidc;

import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.audit.AuditRecord;
import dev.bethge.shipir.audit.AuditService;
import dev.bethge.shipir.auth.LastAdminGuard;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.SessionInvalidator;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Maps a verified identity to a Shipir user. The link is the pair (issuer, subject) and nothing else: a username
 * or email claim never selects an existing account. The role comes from the group mapping on every sign-in,
 * except that the last enabled ADMIN is never demoted or denied.
 */
@Service
class OidcUserResolver {

    private static final Logger log = LoggerFactory.getLogger(OidcUserResolver.class);

    record ResolvedUser(UUID id, String username, boolean provisioned) {
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final SessionInvalidator sessions;
    private final LastAdminGuard lastAdmin;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    OidcUserResolver(UserRepository users, PasswordEncoder encoder, Clock clock,
                     SessionInvalidator sessions, LastAdminGuard lastAdmin, AuditService audit) {
        this.audit = audit;
        this.sessions = sessions;
        this.lastAdmin = lastAdmin;
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Transactional
    ResolvedUser resolve(OidcConfig config, String subject, String preferredName, Collection<String> groups) {
        Optional<Role> role = RoleMapping.resolve(config.roleMapping(), groups);
        Optional<User> existing = users.findByExternalIssuerAndExternalSubject(config.issuer(), subject);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.isDisabled()) {
                throw new OidcException("USER_DISABLED", "The account is disabled");
            }
            if (user.getRole() == Role.ADMIN && role.map(r -> r != Role.ADMIN).orElse(true)
                    && lastAdmin.isOnlyEnabledAdmin(user)) {
                // Decided in shipr-kqbc.62: the mapping never locks out the instance. Keep ADMIN, leave a trace.
                keepLastAdmin(user, role.orElse(null));
                return new ResolvedUser(user.getId(), user.getUsername(), false);
            }
            Role mapped = role.orElseThrow(this::roleDenied);
            if (user.getRole() != mapped) {
                user.changeRole(mapped);
                // Sessions carry the old authorities; the new login creates its session after this call.
                sessions.expireSessionsOf(user.getUsername());
            }
            return new ResolvedUser(user.getId(), user.getUsername(), false);
        }
        if (!config.autoProvision()) {
            throw new OidcException("USER_NOT_PROVISIONED", "No Shipir account is linked to this identity");
        }
        Role mapped = role.orElseThrow(this::roleDenied);
        String username = freeUsername(preferredName, subject);
        // Nobody ever learns this secret: the account can only be entered through the provider.
        String unusable = encoder.encode(Base64.getEncoder().encodeToString(randomBytes()));
        try {
            User user = users.saveAndFlush(User.external(username, unusable, mapped, config.issuer(), subject,
                    clock.instant()));
            return new ResolvedUser(user.getId(), user.getUsername(), true);
        } catch (DataIntegrityViolationException e) {
            throw new OidcException("PROVISIONING_FAILED", "The account could not be created");
        }
    }

    private void keepLastAdmin(User user, Role mapped) {
        log.warn("SSO group mapping would {} the last enabled ADMIN (user id {}); the ADMIN role is kept",
                mapped == null ? "deny" : "demote to " + mapped, user.getId());
        audit.record(new AuditRecord(user.getId(), user.getUsername(), "OIDC_ROLE_KEPT_LAST_ADMIN",
                AuditLogEntry.Outcome.SUCCESS, "USER", user.getId().toString(),
                Map.of("mappedRole", mapped == null ? "NONE" : mapped.name())));
    }

    private OidcException roleDenied() {
        return new OidcException("ROLE_DENIED", "None of the user's groups maps to a Shipir role");
    }

    /** The provider's preferred name made safe for a username; on a clash (ignoring case, so 'Admin' never sits beside 'admin') a short hash of the subject is appended. */
    private String freeUsername(String preferred, String subject) {
        String base = preferred.replaceAll("[^A-Za-z0-9._@-]", "_");
        if (base.isBlank()) {
            base = "sso";
        }
        base = base.length() > 50 ? base.substring(0, 50) : base;
        // "system" is the audit actor of background jobs (see UserAdminService): never hand it to a provider-chosen name
        if (!"system".equalsIgnoreCase(base) && !users.existsByUsernameIgnoreCase(base)) {
            return base;
        }
        String suffix = "-" + sha256Hex(subject).substring(0, 8);
        String candidate = base.substring(0, Math.min(base.length(), 50 - suffix.length())) + suffix;
        if (users.existsByUsernameIgnoreCase(candidate)) {
            throw new OidcException("PROVISIONING_FAILED", "The account could not be created");
        }
        return candidate;
    }

    private byte[] randomBytes() {
        byte[] b = new byte[48];
        random.nextBytes(b);
        return b;
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
