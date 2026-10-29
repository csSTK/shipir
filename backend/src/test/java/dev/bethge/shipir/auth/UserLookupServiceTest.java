package dev.bethge.shipir.auth;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserLookupServiceTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final UserLookupService lookup = new UserLookupService(repository);

    @Test
    void idOfReturnsTheUsersId() {
        User user = mock(User.class);
        UUID id = UUID.randomUUID();
        when(user.getId()).thenReturn(id);
        when(repository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThat(lookup.idOf("alice")).isEqualTo(id);
    }

    @Test
    void unknownUserFailsInsteadOfFallingBackToAnotherUser() {
        when(repository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThat(lookup.findByUsername("ghost")).isEmpty();
        assertThatThrownBy(() -> lookup.idOf("ghost")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> lookup.requireByUsername("ghost")).isInstanceOf(NoSuchElementException.class);
    }
}
