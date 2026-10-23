package dev.bethge.shipir.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.password-reset.*}. */
@ConfigurationProperties("shipir.password-reset")
public record PasswordResetProperties(@DefaultValue("5m") Duration codeTtl, @DefaultValue("5") int maxAttempts) {
}
