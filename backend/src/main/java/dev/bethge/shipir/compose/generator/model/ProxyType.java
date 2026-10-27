package dev.bethge.shipir.compose.generator.model;

/** Reverse proxies the generator can write routing labels for. */
public enum ProxyType {
    TRAEFIK,
    /** caddy-docker-proxy (lucaslorentz), label based like Traefik. */
    CADDY
}
