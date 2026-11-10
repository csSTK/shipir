package dev.bethge.shipir.common.config;

import dev.bethge.shipir.common.util.SecureFiles;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Without SecureDirectoryStream every config-editor and compose file request would fail at runtime: fail at startup. */
class SecureFilesStartupCheckTest {

    @Test
    void thisPlatformSupportsSecureDirectoryAccess() {
        assertThat(SecureFiles.isSupported()).isTrue();
    }

    @Test
    void startupPassesWhenSupported() {
        assertThatCode(() -> new SecureFilesStartupCheck(() -> true).verify()).doesNotThrowAnyException();
    }

    @Test
    void startupFailsFastWithAClearMessageWhenUnsupported() {
        assertThatThrownBy(() -> new SecureFilesStartupCheck(() -> false).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SecureDirectoryStream");
    }

    @Test
    void theDefaultCheckUsesThePlatformProbe() {
        assertThatCode(() -> new SecureFilesStartupCheck().verify()).doesNotThrowAnyException();
    }
}
