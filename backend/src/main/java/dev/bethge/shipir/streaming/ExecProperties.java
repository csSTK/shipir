package dev.bethge.shipir.streaming;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.streaming.exec.*}: interactive console limits. */
@ConfigurationProperties("shipir.streaming.exec")
public record ExecProperties(
        /** A console without any input (keystroke or resize) for this long is closed. */
        @DefaultValue("15m") Duration idleTimeout,
        /** Open consoles per user. */
        @DefaultValue("3") int maxSessionsPerUser,
        /** Output kept for a client that has not subscribed yet; the oldest bytes are dropped first. */
        @DefaultValue("262144") int maxBufferBytes,
        /** Largest accepted input frame. */
        @DefaultValue("65536") int maxInputBytes,
        /** How often idle and abandoned consoles are looked for. */
        @DefaultValue("10s") Duration sweepInterval) {
}
