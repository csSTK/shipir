package dev.bethge.shipir.common.security;

/** Port for the setup gate: whether any Docker environment exists. Implemented by host/, so auth and common don't depend on it. */
public interface EnvironmentPresence {

    boolean hasAnyHost();
}
