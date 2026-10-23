package dev.bethge.shipir.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method whose invocation is written to the audit log by {@link AuditAspect}.
 * The SpEL expressions see the method parameters by name and, for {@code targetId}, {@code #result}.
 * Expressions must never yield secrets (passwords, codes, PEMs).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Auditable {

    /** Action code, e.g. {@code HOST_CREATE}. */
    String action();

    String targetType() default "";

    /** SpEL for the target id, e.g. {@code #id} or {@code #result.id}; empty for none. */
    String targetId() default "";

    /** SpEL for the username when nobody is authenticated (login attempt, password reset). */
    String username() default "";
}
