package dev.bethge.shipir.auth;

import dev.bethge.shipir.common.security.EnvironmentPresence;
import dev.bethge.shipir.common.security.SetupStatus;
import dev.bethge.shipir.common.security.SetupStep;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** PASSWORD while the password is unchanged; ENVIRONMENT for an ADMIN while no environment exists; else none. */
@Component
class UserSetupStatus implements SetupStatus {

    private final UserRepository users;
    private final EnvironmentPresence environments;

    UserSetupStatus(UserRepository users, EnvironmentPresence environments) {
        this.users = users;
        this.environments = environments;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SetupStep> currentStep(String username) {
        return users.findByUsername(username).flatMap(this::stepFor);
    }

    Optional<SetupStep> stepFor(User user) {
        if (user.isSetupRequired()) {
            return Optional.of(SetupStep.PASSWORD);
        }
        if (user.getRole() == Role.ADMIN && !environments.hasAnyHost()) {
            return Optional.of(SetupStep.ENVIRONMENT);
        }
        return Optional.empty();
    }
}
