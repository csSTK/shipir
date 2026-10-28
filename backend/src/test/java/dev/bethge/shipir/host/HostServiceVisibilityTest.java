package dev.bethge.shipir.host;

import dev.bethge.shipir.permission.CurrentAccess;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

/** {@link HostService#isVisible}: a host the user may not see behaves as not found. */
class HostServiceVisibilityTest {

    private final DockerHostRepository hosts = mock(DockerHostRepository.class);
    private final CurrentAccess access = mock(CurrentAccess.class);
    private final HostService service =
            new HostService(hosts, null, null, null, null, access, null, null, null, mock(
                    org.springframework.transaction.PlatformTransactionManager.class));
    private final UUID id = UUID.randomUUID();

    @Test
    void existingAndViewableHostIsVisible() {
        when(hosts.existsById(id)).thenReturn(true);
        when(access.canView(id)).thenReturn(true);

        assertThat(service.isVisible(id)).isTrue();
    }

    @Test
    void existingButNotViewableHostIsNotVisible() {
        when(hosts.existsById(id)).thenReturn(true);
        when(access.canView(id)).thenReturn(false);

        assertThat(service.isVisible(id)).isFalse();
    }

    @Test
    void missingHostIsNotVisibleWithoutAskingAccess() {
        when(hosts.existsById(id)).thenReturn(false);

        assertThat(service.isVisible(id)).isFalse();
        verify(access, never()).canView(id);
    }
}
