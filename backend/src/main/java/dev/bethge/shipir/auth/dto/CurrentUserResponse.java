package dev.bethge.shipir.auth.dto;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;

public record CurrentUserResponse(String username, Role role, boolean setupRequired) {

    public static CurrentUserResponse from(User user) {
        return new CurrentUserResponse(user.getUsername(), user.getRole(), user.isSetupRequired());
    }
}
