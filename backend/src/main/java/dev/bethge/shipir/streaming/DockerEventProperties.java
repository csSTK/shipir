package dev.bethge.shipir.streaming;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.streaming.events.*}: Docker event topic tuning. */
@ConfigurationProperties("shipir.streaming.events")
public record DockerEventProperties(
        /** Events arriving within this window are sent as one EVENT frame. */
        @DefaultValue("300ms") Duration debounce,
        /** Distinct (type, action, id) entries per frame; the rest is only counted in {@code omitted}. */
        @DefaultValue("100") int maxEventsPerFrame,
        /** First wait before reconnecting to a host whose event stream dropped; doubles per failure. */
        @DefaultValue("1s") Duration reconnectInitial,
        /** Upper bound of the reconnect wait. */
        @DefaultValue("30s") Duration reconnectMax) {
}
