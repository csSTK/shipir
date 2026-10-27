package dev.bethge.shipir.compose.generator.model;

/**
 * Public domain of a service behind a reverse proxy. {@code port} is the container port the proxy forwards to.
 * {@code network} is the (external) network the proxy container is attached to; when set, the service joins it so the
 * proxy can reach it. Null means the service is already reachable from the proxy.
 */
public record DomainSpec(String host, Integer port, ProxyType proxy, String network) {
}
