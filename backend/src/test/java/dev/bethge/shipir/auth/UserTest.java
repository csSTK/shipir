package dev.bethge.shipir.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void localUserIsNotExternal() {
        User user = new User("alice", "hash", Role.VIEWER);

        assertThat(user.isExternal()).isFalse();
    }

    @Test
    void userWithIssuerIsExternal() {
        User user = User.external("bob", "hash", Role.OPERATOR, "https://idp.example.com", "sub-1", Instant.now());

        assertThat(user.isExternal()).isTrue();
    }
}
