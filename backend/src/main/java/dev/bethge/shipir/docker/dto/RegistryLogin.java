package dev.bethge.shipir.docker.dto;

/** Registry login handed to the daemon for one image pull; {@code secret} is plaintext and never logged. */
public record RegistryLogin(String registryHost, String username, String secret) {

    @Override
    public String toString() {
        return "RegistryLogin[registryHost=" + registryHost + ", username=" + username + ", secret=<hidden>]";
    }
}
