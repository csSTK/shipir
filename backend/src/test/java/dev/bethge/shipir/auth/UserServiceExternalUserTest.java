package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class UserServiceExternalUserTest {

    private static final String PASSWORD = "Old-Passw0rd-for-tests!";
    private static final String NEW_PASSWORD = "A-brand-new-Passw0rd!";

    @Autowired
    private UserService service;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder encoder;

    @Test
    void changePasswordIsRefusedForExternalUserEvenWithMatchingCurrentPassword() {
        String name = "sso-" + UUID.randomUUID().toString().substring(0, 8);
        users.save(User.external(name, encoder.encode(PASSWORD), Role.OPERATOR, "https://idp.example",
                "sub-" + name, Instant.now()));
        String hashBefore = users.findByUsername(name).orElseThrow().getPasswordHash();

        assertThatThrownBy(() -> service.changePassword(name, PASSWORD, NEW_PASSWORD))
                .isInstanceOf(UserService.InvalidCurrentPasswordException.class);
        assertThat(users.findByUsername(name).orElseThrow().getPasswordHash()).isEqualTo(hashBefore);
    }
}
