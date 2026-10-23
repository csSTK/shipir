package dev.bethge.shipir.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Size(max = 1024) String password) {

    /** Never print the password. */
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + "]";
    }
}
