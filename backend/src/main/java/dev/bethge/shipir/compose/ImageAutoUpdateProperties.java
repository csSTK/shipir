package dev.bethge.shipir.compose;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Bound from {@code shipir.image-auto-update.*}: {@code enabled} switches the scheduler, {@code tick} is its interval,
 * {@code healthTimeout} how long the new containers get to become healthy, {@code stableFor} how long a container
 * without healthcheck must stay running, {@code healthPoll} the pause between checks, {@code runTimeout} the cap for
 * waiting on pull and up.
 */
@ConfigurationProperties("shipir.image-auto-update")
record ImageAutoUpdateProperties(@DefaultValue("true") boolean enabled, @DefaultValue("60s") Duration tick,
                                 @DefaultValue("2m") Duration healthTimeout, @DefaultValue("30s") Duration stableFor,
                                 @DefaultValue("5s") Duration healthPoll, @DefaultValue("20m") Duration runTimeout) {
}
