package dev.bethge.shipir.auth.dto;

import dev.bethge.shipir.auth.Role;

import java.util.UUID;

/** Returned once on create and on password reset; the password is never stored in plain text or logged. */
public record OneTimePasswordResponse(UUID id, String username, Role role, String oneTimePassword) {

    @Override
    public String toString() {
        return "OneTimePasswordResponse[id=" + id + ", username=" + username + ", role=" + role
                + ", oneTimePassword=<hidden>]";
    }
}
