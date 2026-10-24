package dev.bethge.shipir.registry;

/** Decrypted credential for one registry host. Handle with care: {@code secret} is plaintext. */
public record RegistryAuth(String registryHost, String username, String secret) {

    @Override
    public String toString() {
        return "RegistryAuth[registryHost=" + registryHost + ", username=" + username + ", secret=<hidden>]";
    }
}
