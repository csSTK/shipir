package dev.bethge.shipir.proxy.render;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.proxy.ProxyCertificate;
import dev.bethge.shipir.proxy.ProxyInstance;
import dev.bethge.shipir.proxy.ProxyRoute;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure renderer of the managed proxy configuration from a host's proxy instance, routes and certificates. It writes
 * nothing and decrypts nothing: custom certificates are referenced by the file paths below, which the service that
 * deploys the configuration fills with the certificate and (decrypted) key. The user-editable custom file lives next
 * to the managed file and is never produced here.
 *
 * <p>Routes are expected to have passed {@code ProxyRouteValidator}. The renderers still check every value they emit
 * against a strict charset or escape it for the output format, and throw {@link IllegalArgumentException} for control
 * characters (so a user value can never start a new line). Output is deterministic: routes are ordered by hostname,
 * maps by key.
 */
public interface ProxyConfigRenderer {

    /** Directory of the proxy container's config volume (see the bundled templates). */
    String CONFIG_DIR = "/etc/shipir-proxy";

    /** Where the certificate of a custom certificate is expected inside the proxy container. */
    static String certFile(UUID certificateId) {
        return CONFIG_DIR + "/certs/" + certificateId + ".crt";
    }

    /** Where the private key of a custom certificate is expected inside the proxy container. */
    static String keyFile(UUID certificateId) {
        return CONFIG_DIR + "/certs/" + certificateId + ".key";
    }

    ProxyType type();

    /**
     * @param certificates the host's certificates by id; every certificate referenced by a CUSTOM route must be present
     * @return the managed files (never the custom file)
     * @throws IllegalArgumentException if a route cannot be rendered safely or for this proxy
     */
    List<RenderedFile> render(ProxyInstance instance, List<ProxyRoute> routes, Map<UUID, ProxyCertificate> certificates);
}
