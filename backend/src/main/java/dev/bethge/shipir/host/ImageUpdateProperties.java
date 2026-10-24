package dev.bethge.shipir.host;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.images.*}. */
@ConfigurationProperties("shipir.images")
record ImageUpdateProperties(@DefaultValue("6h") Duration updateCheckInterval,
                             @DefaultValue("true") boolean updateCheckEnabled) {
}
