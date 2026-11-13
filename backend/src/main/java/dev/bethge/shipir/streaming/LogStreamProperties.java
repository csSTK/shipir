package dev.bethge.shipir.streaming;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.streaming.logs.*}: container log topic limits. */
@ConfigurationProperties("shipir.streaming.logs")
public record LogStreamProperties(
        /** Lines sent when the first subscriber arrives. */
        @DefaultValue("200") int tail,
        /** Lines kept per followed container for subscribers that join later. */
        @DefaultValue("500") int bufferLines,
        /** Longer lines are cut and marked. */
        @DefaultValue("8192") int maxLineLength,
        /** Lines above this rate per container are dropped and summarized in one marker line. */
        @DefaultValue("1000") int maxLinesPerSecond,
        /**
         * Docker evaluates {@code since} against the daemon clock, not Shipir's. When a re-follow after an ended
         * stream has no daemon timestamp to start from (timestamps off), {@code since} is the Shipir end time minus
         * this margin, so a daemon clock behind Shipir's by up to the margin loses no lines; the price is repeated
         * lines (up to the margin's worth). Should be at least the expected clock skew between Shipir and its hosts.
         */
        @DefaultValue("5s") Duration sinceSafetyMargin) {

    public LogStreamProperties {
        if (sinceSafetyMargin == null) {
            sinceSafetyMargin = Duration.ofSeconds(5);
        } else if (sinceSafetyMargin.isNegative()) {
            throw new IllegalArgumentException("shipir.streaming.logs.since-safety-margin must not be negative");
        }
    }
}
