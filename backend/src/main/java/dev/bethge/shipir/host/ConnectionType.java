package dev.bethge.shipir.host;

/** How Shipir reaches a Docker daemon. */
public enum ConnectionType {
    /** Unix socket of the machine Shipir runs on. */
    LOCAL,
    /** Remote daemon over TCP with TLS client certificates. */
    TCP
}
