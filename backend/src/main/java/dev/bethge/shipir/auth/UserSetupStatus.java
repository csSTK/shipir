package dev.bethge.shipir.auth;

import dev.bethge.shipir.common.security.SetupStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class UserSetupStatus implements SetupStatus {

    private final UserRepository users;

    UserSetupStatus(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSetupRequired(String username) {
        return users.findByUsername(username).map(User::isSetupRequired).orElse(false);
    }
}
