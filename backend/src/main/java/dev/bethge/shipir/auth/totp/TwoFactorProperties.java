package dev.bethge.shipir.auth.totp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Bound from {@code shipir.two-factor.*}: wrong codes per user before a lockout, its length, and how long a
 * password-verified login may wait for the second step.
 */
@ConfigurationProperties("shipir.two-factor")
public record TwoFactorProperties(@DefaultValue("5") int maxAttempts, @DefaultValue("5m") Duration lockout,
                                  @DefaultValue("5m") Duration pendingTtl) {
}
