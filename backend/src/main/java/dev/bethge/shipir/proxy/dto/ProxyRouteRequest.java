package dev.bethge.shipir.proxy.dto;

import dev.bethge.shipir.proxy.BackendScheme;
import dev.bethge.shipir.proxy.CertMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publish or update a route. Only {@code hostname} and {@code containerName} are required for a simple publish;
 * everything else is optional and falls back to a default ({@code port}: detected, {@code certMode}: the instance
 * default, {@code backendScheme}: HTTP, {@code redirectHttps}: true). The shape is checked by
 * {@code ProxyRouteValidator}; a plain-text {@code basicAuth} password is hashed and never stored or returned.
 * On update (PUT) the fields {@code containerName}, {@code port}, {@code certMode} and {@code basicAuth} keep the
 * stored value when null; every other field is replaced as sent, so null resets it to its default (no aliases, HTTP,
 * no path prefix/entrypoint/certificate/headers, redirect on). For {@code basicAuth}, an empty map removes all users
 * and a null or blank password keeps that existing user's hash. {@code toString} hides the passwords.
 */
public record ProxyRouteRequest(
        @NotBlank @Size(max = 253) String hostname,
        @Size(max = 255) String containerName,
        Integer port,
        List<String> aliases,
        BackendScheme backendScheme,
        @Size(max = 255) String pathPrefix,
        @Size(max = 50) String entrypoint,
        CertMode certMode,
        UUID certificateId,
        Boolean redirectHttps,
        Map<String, String> basicAuth,
        Map<String, String> responseHeaders) {

    @Override
    public String toString() {
        return "ProxyRouteRequest[hostname=" + hostname + ", containerName=" + containerName + ", port=" + port + "]";
    }
}
