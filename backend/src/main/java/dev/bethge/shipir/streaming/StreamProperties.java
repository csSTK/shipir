package dev.bethge.shipir.streaming;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.streaming.*}: replay buffer per deployment. */
@ConfigurationProperties("shipir.streaming")
public record StreamProperties(
        @DefaultValue("2000") int bufferLines,
        @DefaultValue("10m") Duration retention,
        @DefaultValue("200") int maxStreams) {
}
