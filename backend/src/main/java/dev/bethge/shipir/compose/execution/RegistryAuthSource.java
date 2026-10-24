package dev.bethge.shipir.compose.execution;

import java.util.List;

/**
 * Port: registry logins that every compose subprocess should be able to use for image pulls. Implemented by
 * {@code registry/}; must return an empty list (never throw) when there are none or they cannot be decrypted.
 */
@FunctionalInterface
public interface RegistryAuthSource {

    List<Auth> auths();

    /** One registry login; {@code secret} is plaintext, never log it. */
    record Auth(String registryHost, String username, String secret) {

        @Override
        public String toString() {
            return "Auth[registryHost=" + registryHost + ", username=" + username + ", secret=<hidden>]";
        }
    }
}
