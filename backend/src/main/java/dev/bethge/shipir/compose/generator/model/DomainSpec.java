package dev.bethge.shipir.compose.generator.model;

/**
 * Public domain of a service behind a reverse proxy. {@code port} is the container port the proxy forwards to.
 * {@code network} is the (external) network the proxy container is attached to; when set, the service joins it so the
 * proxy can reach it. Null means the service is already reachable from the proxy.
 * {@code entrypoint} and {@code certResolver} are Traefik-only names of the proxy's HTTPS entrypoint and ACME resolver;
 * null means Shipir's own template defaults ({@code websecure}, {@code letsencrypt}).
 */
public record DomainSpec(String host, Integer port, ProxyType proxy, String network, String entrypoint,
                         String certResolver) {

    public static final String DEFAULT_ENTRYPOINT = "websecure";
    public static final String DEFAULT_CERT_RESOLVER = "letsencrypt";

    public DomainSpec(String host, Integer port, ProxyType proxy, String network) {
        this(host, port, proxy, network, null, null);
    }
}
