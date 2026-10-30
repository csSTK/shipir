package dev.bethge.shipir.proxy.dto;

import dev.bethge.shipir.proxy.BackendScheme;
import dev.bethge.shipir.proxy.CertMode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One published route. {@code basicAuthUsers} holds user names only, never hashes. {@code advancedActive} is true when
 * any setting beyond hostname, container and port differs from the simple default. {@code containerState} is null
 * when the container no longer exists.
 */
public record ProxyRouteView(UUID id, String hostname, List<String> aliases, String containerName, int targetPort,
                             BackendScheme backendScheme, String pathPrefix, String entrypoint, CertMode certMode,
                             UUID certificateId, boolean redirectHttps, List<String> basicAuthUsers,
                             Map<String, String> responseHeaders, boolean advancedActive, String containerState,
                             Instant createdAt, Instant updatedAt) {
}
