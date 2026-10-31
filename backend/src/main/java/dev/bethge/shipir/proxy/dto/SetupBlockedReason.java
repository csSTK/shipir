package dev.bethge.shipir.proxy.dto;

/** Why the managed proxy cannot be set up on a host. */
public enum SetupBlockedReason {
    /** Another Traefik or caddy-docker-proxy already runs on the host. */
    EXISTING_PROXY,
    /** Port 80 or 443 is already published by a container. */
    PORTS_IN_USE,
    /** The caller does not see the whole host (deployment grants only), so nothing could be checked. */
    UNKNOWN
}
