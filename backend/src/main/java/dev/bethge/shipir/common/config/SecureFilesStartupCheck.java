package dev.bethge.shipir.common.config;

import dev.bethge.shipir.common.util.SecureFiles;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.BooleanSupplier;

/**
 * Fails the application start when the platform has no {@code SecureDirectoryStream}: {@link SecureFiles} fails closed
 * without it, so every config-editor and compose file operation would otherwise error at runtime, request by request.
 */
@Component
class SecureFilesStartupCheck {

    private final BooleanSupplier supported;

    @Autowired
    SecureFilesStartupCheck() {
        this(SecureFiles::isSupported);
    }

    SecureFilesStartupCheck(BooleanSupplier supported) {
        this.supported = supported;
    }

    @PostConstruct
    void verify() {
        if (!supported.getAsBoolean()) {
            throw new IllegalStateException("This platform's file system offers no SecureDirectoryStream, which Shipir needs "
                    + "for symlink-safe file access (config editor, compose files). Run it on Linux or macOS.");
        }
    }
}
