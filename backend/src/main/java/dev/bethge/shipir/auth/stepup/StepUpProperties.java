package dev.bethge.shipir.auth.stepup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Bound from {@code shipir.security.step-up.*}. {@code ssoMaxAge}: how old the IdP's {@code auth_time} may be for an
 * SSO user to pass a step-up; also sent to the provider as {@code max_age} when re-authentication is started.
 */
@ConfigurationProperties("shipir.security.step-up")
public record StepUpProperties(@DefaultValue("5m") Duration ssoMaxAge) {
}
