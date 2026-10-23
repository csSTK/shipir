package dev.bethge.shipir.audit;

import dev.bethge.shipir.auth.UserRepository;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes one audit entry per {@link Auditable} call: SUCCESS after a normal return (a ResponseEntity with an
 * error status counts as FAILURE), FAILURE when an exception is thrown, which is rethrown unchanged.
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
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable t) {
            record(pjp, auditable, before, null, AuditLogEntry.Outcome.FAILURE);
            throw t;
        }
        boolean failed = result instanceof ResponseEntity<?> r && (r.getStatusCode().isError());
        record(pjp, auditable, before, result,
                failed ? AuditLogEntry.Outcome.FAILURE : AuditLogEntry.Outcome.SUCCESS);
        return result;
    }

    private void record(ProceedingJoinPoint pjp, Auditable a, String nameBefore, Object result,
                        AuditLogEntry.Outcome outcome) {
        try {
            EvaluationContext ctx = new MethodBasedEvaluationContext(pjp.getTarget(),
                    ((MethodSignature) pjp.getSignature()).getMethod(), pjp.getArgs(), names);
            ctx.setVariable("result", result);
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
            auditService.record(new AuditRecord(userId, truncate(username, 50), a.action(), outcome,
                    targetType, eval(a.targetId(), ctx), aiDetails()));
        } catch (RuntimeException e) {
            log.warn("Could not write audit entry for {}: {}", a.action(), e.toString());
        }
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
        Object value = parser.parseExpression(expression).getValue(ctx);
        return value == null ? null : value.toString();
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
