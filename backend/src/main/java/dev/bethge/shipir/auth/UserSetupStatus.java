package dev.bethge.shipir.auth;

import dev.bethge.shipir.auth.totp.SecuritySettingsService;
import dev.bethge.shipir.common.security.EnvironmentPresence;
import dev.bethge.shipir.common.security.SetupStatus;
import dev.bethge.shipir.common.security.SetupStep;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** PASSWORD while the password is unchanged; TWO_FACTOR for an ADMIN without 2FA while the security setting requires it; ENVIRONMENT for an ADMIN while no environment exists; else none. */
@Component
class UserSetupStatus implements SetupStatus {

    private final UserRepository users;
    private final EnvironmentPresence environments;
    private final SecuritySettingsService securitySettings;

    UserSetupStatus(UserRepository users, EnvironmentPresence environments, SecuritySettingsService securitySettings) {
        this.securitySettings = securitySettings;
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
        if (user.getRole() == Role.ADMIN && !user.isTwoFactorEnabled() && securitySettings.require2faForAdmins()) {
            return Optional.of(SetupStep.TWO_FACTOR);
        }
        if (user.getRole() == Role.ADMIN && !environments.hasAnyHost()) {
            return Optional.of(SetupStep.ENVIRONMENT);
        }
        return Optional.empty();
    }
}
