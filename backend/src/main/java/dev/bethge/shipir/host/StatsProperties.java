package dev.bethge.shipir.host;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.stats.*}: recording of the resource usage history. */
@ConfigurationProperties("shipir.stats")
public record StatsProperties(
        /** Time between two recording ticks; also the finest resolution of the history. */
        @DefaultValue("60s") Duration interval,
        /** Time one host may take per tick (listing and sampling); a slower host is skipped for this tick. */
        @DefaultValue("20s") Duration hostTimeout,
        /** Running containers recorded per host and tick; the rest is skipped. */
        @DefaultValue("200") int maxContainers,
        /** Upper bound of the points per container series returned by the history endpoint. */
        @DefaultValue("300") int maxPoints,
        /** Most container series returned per request; the ones with the most recent samples win. */
        @DefaultValue("50") int maxSeries) {
}
