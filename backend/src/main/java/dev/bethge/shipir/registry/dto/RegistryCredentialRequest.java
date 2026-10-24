package dev.bethge.shipir.registry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create and update body. {@code secret} is required on create; on update null or blank keeps the stored secret. */
public record RegistryCredentialRequest(@NotBlank @Size(max = 255) String name,
                                        @NotBlank @Size(max = 255) String registryHost,
                                        @NotBlank @Size(max = 255) String username,
                                        @Size(max = 1024) String secret) {

    /** The secret is left out. */
    @Override
    public String toString() {
        return "RegistryCredentialRequest[name=" + name + ", registryHost=" + registryHost
                + ", username=" + username + ", secret=<hidden>]";
    }
}
