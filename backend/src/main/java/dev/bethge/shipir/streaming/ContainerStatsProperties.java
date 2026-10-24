package dev.bethge.shipir.streaming;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.streaming.stats.*}: live container stats topic tuning. */
@ConfigurationProperties("shipir.streaming.stats")
public record ContainerStatsProperties(
        /** Time between two frames of a host. */
        @DefaultValue("2s") Duration interval,
        /** Running containers sampled per frame; the rest is skipped. */
        @DefaultValue("200") int maxContainers) {
}
