package dev.bethge.shipir.host;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.host.container-watch.*}; the watcher is off with {@code enabled=false}. */
@ConfigurationProperties("shipir.host.container-watch")
public record ContainerWatchProperties(
        @DefaultValue("true") boolean enabled,
        /** How often the set of watched hosts is reconciled with the host table. */
        @DefaultValue("60s") Duration syncInterval,
        /** The same problem of the same container is reported at most once per window. */
        @DefaultValue("10m") Duration dedupeWindow,
        @DefaultValue("1s") Duration reconnectInitial,
        @DefaultValue("60s") Duration reconnectMax) {
}
