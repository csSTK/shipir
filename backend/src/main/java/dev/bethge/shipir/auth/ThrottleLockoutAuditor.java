package dev.bethge.shipir.auth;

import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.audit.AuditRecord;
import dev.bethge.shipir.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Writes the {@code LOGIN_THROTTLED} audit entry when a throttle scope locks a key: details carry the scope and the
 * client address only, never a credential. A failing audit write is logged and swallowed, like in {@code AuditAspect}:
 * it must not change the response to the client.
 */
@Component
public class ThrottleLockoutAuditor {

    private static final Logger log = LoggerFactory.getLogger(ThrottleLockoutAuditor.class);

    private final AuditService auditService;

    ThrottleLockoutAuditor(AuditService auditService) {
        this.auditService = auditService;
    }

    /** @param subject the locked username, or the client address for scopes without a user (webhook, bearer) */
    public void lockoutStarted(LoginThrottle.Scope scope, String subject, String address) {
        try {
            auditService.record(new AuditRecord(null, subject, "LOGIN_THROTTLED", AuditLogEntry.Outcome.FAILURE,
                    "USER", subject, Map.of("scope", scope.name(), "clientAddress", address)));
        } catch (RuntimeException e) {
            log.warn("Could not write audit entry for LOGIN_THROTTLED: {}", e.toString());
        }
    }
}
