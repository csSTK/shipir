package dev.bethge.shipir.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Bound from {@code shipir.security.*}. {@code trustedProxies} are the exact peer addresses whose
 * {@code X-Forwarded-For} header is believed; empty means the header is ignored.
 */
@ConfigurationProperties("shipir.security")
public record AuthThrottleProperties(@DefaultValue Login login, @DefaultValue List<String> trustedProxies) {

    public record Login(@DefaultValue("5") int maxAttempts,
                        @DefaultValue("15m") Duration window,
                        @DefaultValue("15m") Duration lockout) {
    }
}
