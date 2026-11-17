package dev.bethge.shipir.auth.totp;

import org.springframework.context.ApplicationContext;

/** Reaches the package-private {@link TwoFactorAttemptLimiter} for the shared-context reset between test classes. */
public final class TwoFactorThrottleTestSupport {

    private TwoFactorThrottleTestSupport() {
    }

    /** Forgets every two-factor failure and lockout held in memory by the given context. */
    public static void clear(ApplicationContext context) {
        context.getBeanProvider(TwoFactorAttemptLimiter.class).ifAvailable(TwoFactorAttemptLimiter::clear);
    }
}
