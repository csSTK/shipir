package dev.bethge.shipir.streaming;

import dev.bethge.shipir.auth.Role;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamViewersTest {

    private final StreamAccess access = mock(StreamAccess.class);
    private final StreamViewers viewers = new StreamViewers(access);

    @Test
    void onlyAnEnabledAdminIsAdmin() {
        when(access.roleOf("root")).thenReturn(Optional.of(Role.ADMIN));
        when(access.roleOf("op")).thenReturn(Optional.of(Role.OPERATOR));
        when(access.roleOf("gone")).thenReturn(Optional.empty());
        assertThat(viewers.isAdmin("root")).isTrue();
        assertThat(viewers.isAdmin("op")).isFalse();
        assertThat(viewers.isAdmin("gone")).isFalse();
    }

    @Test
    void failsClosedWhenTheRoleCannotBeRead() {
        when(access.roleOf("root")).thenThrow(new IllegalStateException("db down"));
        assertThat(viewers.isAdmin("root")).isFalse();
    }
}
