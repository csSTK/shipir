package dev.bethge.shipir.auth.token;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Bound from {@code shipir.api-tokens.*}. {@code maxLifetime} caps (and is the default of) a token's expiry;
 * {@code lastUsedResolution} is how stale {@code last_used_at} must be before a request writes it again;
 * {@code maxPerUser} is the most tokens one user may hold at a time.
 */
@ConfigurationProperties("shipir.api-tokens")
public record ApiTokenProperties(@DefaultValue("365d") Duration maxLifetime,
                                 @DefaultValue("5m") Duration lastUsedResolution,
                                 @DefaultValue("20") int maxPerUser) {
}
