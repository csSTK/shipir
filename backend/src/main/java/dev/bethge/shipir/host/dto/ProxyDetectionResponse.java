package dev.bethge.shipir.host.dto;

import dev.bethge.shipir.compose.generator.model.ProxyType;

/**
 * Reverse proxy found on a host. {@code visible} is false when the caller does not see the whole host (only
 * deployment grants), so nothing was looked at and "no proxy" would be a false statement; the other fields are then
 * null. On a fully visible host all other fields are null when no proxy runs. {@code network} is the user-defined
 * network the proxy container is attached to (the alphabetically first one if several, null if it only uses a
 * built-in network).
 */
public record ProxyDetectionResponse(boolean visible, ProxyType proxy, String container, String network) {

    /** Host fully visible, no proxy running. */
    public static ProxyDetectionResponse none() {
        return new ProxyDetectionResponse(true, null, null, null);
    }

    /** Host not fully visible to the caller: detection was not attempted. */
    public static ProxyDetectionResponse notVisible() {
        return new ProxyDetectionResponse(false, null, null, null);
    }
}
