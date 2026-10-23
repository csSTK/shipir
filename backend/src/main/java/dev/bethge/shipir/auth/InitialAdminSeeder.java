package dev.bethge.shipir.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first ADMIN on an empty database. The account must change its password on first
 * login ({@code password_changed_at} stays NULL). Does nothing once any user exists.
 */
@Component
@EnableConfigurationProperties(InitialAdminProperties.class)
class InitialAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialAdminSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final InitialAdminProperties properties;

    InitialAdminSeeder(UserRepository users, PasswordEncoder passwordEncoder, InitialAdminProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        String password = properties.password();
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("No users exist yet and SHIPIR_INITIAL_ADMIN_PASSWORD is not set. "
                    + "Set it to create the initial admin account.");
        }
        users.save(new User(properties.username(), passwordEncoder.encode(password), Role.ADMIN));
        log.info("Created initial admin user '{}'; password change required on first login", properties.username());
    }
}
