package dev.bethge.shipir.architecture.fixture.common.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Allowed: common.util holds the guarded file helpers (PathValidationUtil, SecureFiles) and is unrestricted. */
public class UnrestrictedFileUser {
    String read(Path path) throws IOException {
        return Files.readString(path);
    }
}
