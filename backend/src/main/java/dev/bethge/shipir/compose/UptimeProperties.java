package dev.bethge.shipir.compose;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Bound from {@code shipir.uptime.*}: {@code enabled} switches the scheduler, {@code tick} is how often due checks are
 * looked for, {@code failureThreshold} the consecutive failures before a check is DOWN (recovery needs one success),
 * {@code resultRetention} how long results are kept.
 */
@ConfigurationProperties("shipir.uptime")
record UptimeProperties(@DefaultValue("true") boolean enabled, @DefaultValue("10s") Duration tick,
                        @DefaultValue("2") int failureThreshold, @DefaultValue("14d") Duration resultRetention) {
}
