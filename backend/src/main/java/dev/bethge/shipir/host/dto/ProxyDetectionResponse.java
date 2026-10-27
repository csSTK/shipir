package dev.bethge.shipir.host.dto;

import dev.bethge.shipir.compose.generator.model.ProxyType;

/**
 * Reverse proxy found on a host. All fields are null when none runs. {@code network} is the user-defined network the
 * proxy container is attached to (null if it only uses a built-in network).
 */
public record ProxyDetectionResponse(ProxyType proxy, String container, String network) {

    public static ProxyDetectionResponse none() {
        return new ProxyDetectionResponse(null, null, null);
    }
}
