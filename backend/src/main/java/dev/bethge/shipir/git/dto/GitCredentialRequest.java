package dev.bethge.shipir.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create and update body. {@code host} is the only host the token may be sent to (e.g. github.com or git.example.org:8443).
 * {@code token} is required on create; on update null or blank keeps the stored token. */
public record GitCredentialRequest(@NotBlank @Size(max = 255) String name,
                                   @NotBlank @Size(max = 255) String host,
                                   @NotBlank @Size(max = 255) String username,
                                   @Size(max = 1024) String token) {

    /** The token is left out. */
    @Override
    public String toString() {
        return "GitCredentialRequest[name=" + name + ", host=" + host + ", username=" + username + ", token=<hidden>]";
    }
}
