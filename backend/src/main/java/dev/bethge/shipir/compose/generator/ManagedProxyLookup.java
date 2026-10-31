package dev.bethge.shipir.compose.generator;

import dev.bethge.shipir.compose.generator.model.ProxyType;

import java.util.Optional;
import java.util.UUID;

/**
 * Port: which proxy Shipir manages on a host. Implemented by the {@code proxy} feature, so the generator does not
 * depend on it. Throws {@link HostNotFoundException} when the host does not exist or the caller cannot see it.
 */
public interface ManagedProxyLookup {

    Optional<ProxyType> managedProxyType(UUID hostId);

    /** The environment does not exist or is not visible to the caller. */
    class HostNotFoundException extends RuntimeException {
        public HostNotFoundException() {
            super("Environment not found");
        }
    }
}
