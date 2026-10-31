package dev.bethge.shipir.proxy.config;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.proxy.ProxyException;
import dev.bethge.shipir.proxy.ProxyHostAccess;
import dev.bethge.shipir.proxy.ProxySyncService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Docker failure code in the validation error is only shown to an ADMIN. */
class CaddyCustomConfigValidatorTest {

    private final ProxySyncService sync = mock(ProxySyncService.class);
    private final ContainerFileService files = mock(ContainerFileService.class);
    private final ProxyHostAccess access = mock(ProxyHostAccess.class);
    private final CaddyCustomConfigValidator validator = new CaddyCustomConfigValidator(sync, files, access);

    private void failWith(DockerErrorCode code) {
        when(sync.proxyContainerId(any(), any())).thenReturn("proxy1");
        doThrow(new DockerConnectionException(code, "x", null)).when(files)
                .writeFiles(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void nonAdminDoesNotSeeTheConnectionCode() {
        failWith(DockerErrorCode.TLS_ERROR);
        when(access.isAdmin()).thenReturn(false);
        assertThatThrownBy(() -> validator.validate(UUID.randomUUID(), "example.com {}", List.of()))
                .isInstanceOfSatisfying(ProxyException.class, e -> org.assertj.core.api.Assertions.assertThat(e.getMessage())
                        .contains("UNREACHABLE").doesNotContain("TLS_ERROR"));
    }

    @Test
    void adminSeesTheConnectionCode() {
        failWith(DockerErrorCode.TLS_ERROR);
        when(access.isAdmin()).thenReturn(true);
        assertThatThrownBy(() -> validator.validate(UUID.randomUUID(), "example.com {}", List.of()))
                .isInstanceOfSatisfying(ProxyException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("TLS_ERROR"));
    }
}
