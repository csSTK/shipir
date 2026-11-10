package dev.bethge.shipir.audit;

import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.exception.RequestThrottled;
import dev.bethge.shipir.common.security.AiInvocationContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes one audit entry per {@link Auditable} call: SUCCESS after a normal return (a ResponseEntity with an
 * error status counts as FAILURE, except 429 which is not recorded), FAILURE when an exception is thrown (except a
 * {@link RequestThrottled} one, not recorded), which
 * is rethrown unchanged.
 * Recording problems are logged and swallowed so auditing can never break the audited action.
 */
@Aspect
@Component
class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditService auditService;
    private final UserRepository users;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer names = new DefaultParameterNameDiscoverer();

    AuditAspect(AuditService auditService, UserRepository users) {
        this.auditService = auditService;
        this.users = users;
    }

    @Around("@annotation(auditable)")
    Object audit(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        String before = authenticatedName();
        Object beforeState = captureBefore(pjp, auditable);
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable t) {
            if (t instanceof RequestThrottled) {
                // Throttled (e.g. 2FA lockout): no per-request row; the attempt that started the lockout was audited.
                throw t;
            }
            record(pjp, auditable, before, null, beforeState, AuditLogEntry.Outcome.FAILURE);
            throw t;
        }
        if (result instanceof ResponseEntity<?> r && r.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            // Throttled: no per-request row, or a flood would become a flood of DB writes. The start of a lockout
            // is audited once by the throttle itself (LOGIN_THROTTLED).
            return result;
        }
        boolean failed = result instanceof ResponseEntity<?> r && (r.getStatusCode().isError());
        record(pjp, auditable, before, result, beforeState,
                failed ? AuditLogEntry.Outcome.FAILURE : AuditLogEntry.Outcome.SUCCESS);
        return result;
    }

    private void record(ProceedingJoinPoint pjp, Auditable a, String nameBefore, Object result,
                        Object beforeState, AuditLogEntry.Outcome outcome) {
        try {
            EvaluationContext ctx = context(pjp);
            ctx.setVariable("result", result);
            ctx.setVariable("before", beforeState);
            // Failed attempts never carry a user id, even if the attempted username exists.
            String authName = outcome == AuditLogEntry.Outcome.SUCCESS && authenticatedName() != null
                    ? authenticatedName() : nameBefore;
            String username = authName;
            if (username == null) {
                username = eval(a.username(), ctx);
            }
            if (username == null) {
                username = "anonymous";
            }
            UUID userId = authName == null ? null
                    : users.findByUsername(authName).map(u -> u.getId()).orElse(null);
            String targetType = a.targetType().isEmpty() ? null : a.targetType();
            AuditRecord entry = new AuditRecord(userId, truncate(username, 50), a.action(), outcome,
                    targetType, eval(a.targetId(), ctx), details(a, ctx, outcome));
            AuditRecord notCommitted = outcome == AuditLogEntry.Outcome.SUCCESS
                    ? new AuditRecord(null, entry.username(), entry.action(), AuditLogEntry.Outcome.FAILURE,
                            entry.targetType(), entry.targetId(),
                            details(a, ctx, AuditLogEntry.Outcome.FAILURE))
                    : entry;
            writeAfterTransaction(entry, notCommitted, a.action());
        } catch (RuntimeException e) {
            log.warn("Could not write audit entry for {}: {}", a.action(), e.toString());
        }
    }

    /**
     * The entry is built while the audited call's data is at hand, but written only once the surrounding
     * transaction (if the audited method is transactional) has committed or rolled back. The write uses its own
     * transaction with a foreign key to the acting user; if the audited method updated an indexed column of that
     * user's own row, its still open transaction holds a lock the FK check would wait on forever (self-deadlock).
     * Trade-off: a success entry is written a moment after the commit instead of before it, so a crash in
     * between loses the entry; failure entries are kept because they are written after the rollback.
     */
    private void writeAfterTransaction(AuditRecord entry, AuditRecord notCommitted, String action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            auditService.record(entry);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    // The method returned normally but the transaction did not commit (rollback-only, commit-time
                    // failure): the action did not happen, so record a failed attempt (no user id, no success details).
                    auditService.record(status == STATUS_COMMITTED ? entry : notCommitted);
                } catch (RuntimeException e) {
                    log.warn("Could not write audit entry for {}: {}", action, e.toString());
                }
            }
        });
    }

    private EvaluationContext context(ProceedingJoinPoint pjp) {
        return new MethodBasedEvaluationContext(pjp.getTarget(),
                ((MethodSignature) pjp.getSignature()).getMethod(), pjp.getArgs(), names);
    }

    /** Previous state for {@code details}; a failing expression is logged and yields null. */
    private Object captureBefore(ProceedingJoinPoint pjp, Auditable a) {
        if (a.before().isEmpty()) {
            return null;
        }
        try {
            return parser.parseExpression(a.before()).getValue(context(pjp));
        } catch (RuntimeException e) {
            log.warn("Could not capture previous state for {}: {}", a.action(), e.toString());
            return null;
        }
    }

    /** Declared details (successful calls only) merged with the AI marker; null when both are empty. */
    private Map<String, Object> details(Auditable a, EvaluationContext ctx, AuditLogEntry.Outcome outcome) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (!a.details().isEmpty() && outcome == AuditLogEntry.Outcome.SUCCESS) {
            try {
                Object value = parser.parseExpression(a.details()).getValue(ctx);
                if (value instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> details.put(String.valueOf(k), v));
                }
            } catch (RuntimeException e) {
                log.warn("Could not evaluate audit details for {}: {}", a.action(), e.toString());
            }
        }
        Map<String, Object> ai = aiDetails();
        if (ai != null) {
            details.putAll(ai);
        }
        return details.isEmpty() ? null : details;
    }

    /** {via: AI, chatSessionId} while the AI assistant acts for the user, null for normal requests. */
    private static Map<String, Object> aiDetails() {
        if (!AiInvocationContext.active()) {
            return null;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("via", "AI");
        AiInvocationContext.chatSessionId().ifPresent(id -> details.put("chatSessionId", id.toString()));
        return details;
    }

    private String eval(String expression, EvaluationContext ctx) {
        if (expression.isEmpty()) {
            return null;
        }
        try {
            Object value = parser.parseExpression(expression).getValue(ctx);
            return value == null ? null : value.toString();
        } catch (RuntimeException e) {
            // E.g. "#result.id()" after a failed call: keep the entry, leave the field empty.
            log.warn("Could not evaluate audit expression '{}': {}", expression, e.toString());
            return null;
        }
    }

    private static String authenticatedName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth.getName();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
