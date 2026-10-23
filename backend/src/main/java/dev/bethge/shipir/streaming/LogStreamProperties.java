package dev.bethge.shipir.streaming;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
        @DefaultValue("1000") int maxLinesPerSecond) {
}
