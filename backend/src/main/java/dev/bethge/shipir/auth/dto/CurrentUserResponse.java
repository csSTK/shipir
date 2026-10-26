package dev.bethge.shipir.auth.dto;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.common.security.SetupStep;

import java.util.Optional;

/**
 * {@code setupStep} is null once setup is complete; {@code externalIssuer} is set only for single sign-on users; {@code locale} is the chosen UI language or null.
 */
public record CurrentUserResponse(String username, Role role, boolean setupRequired, SetupStep setupStep,
                                  boolean twoFactorEnabled, String externalIssuer, String locale) {

    public static CurrentUserResponse from(User user, Optional<SetupStep> step) {
        return from(user, step, user.getRole());
    }

    /** With the role the caller acts under (an API token's is capped, see {@code ApiTokenAuthentication}). */
    public static CurrentUserResponse from(User user, Optional<SetupStep> step, Role effectiveRole) {
        return new CurrentUserResponse(user.getUsername(), effectiveRole, step.isPresent(), step.orElse(null),
                user.isTwoFactorEnabled(), user.getExternalIssuer(), user.getLocale());
    }
}
