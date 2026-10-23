package dev.bethge.shipir.auth.dto;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.common.security.SetupStep;

import java.util.Optional;

/** {@code setupStep} is null once the user's first-start setup is complete. */
public record CurrentUserResponse(String username, Role role, boolean setupRequired, SetupStep setupStep) {

    public static CurrentUserResponse from(User user, Optional<SetupStep> step) {
        return new CurrentUserResponse(user.getUsername(), user.getRole(), step.isPresent(), step.orElse(null));
    }
}
