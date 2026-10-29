package dev.bethge.shipir.auth.dto;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code passwordChangeRequired} is true until the user has chosen their own password; {@code passwordChangedAt}
 * is null for exactly that period. {@code externalIdentity} is true for SSO users (no local password); only the
 * flag is exposed, never issuer or subject.
 */
public record UserResponse(UUID id, String username, Role role, boolean disabled, boolean passwordChangeRequired,
                           Instant createdAt, Instant passwordChangedAt, Instant disabledAt,
                           boolean twoFactorEnabled, boolean externalIdentity) {

    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getRole(), u.isDisabled(), u.isSetupRequired(),
                u.getCreatedAt(), u.getPasswordChangedAt(), u.getDisabledAt(),
                u.isTwoFactorEnabled(), u.isExternal());
    }
}
