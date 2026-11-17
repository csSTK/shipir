package dev.bethge.shipir.architecture.fixture.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deliberate violation: reads a file with Files outside common.util and not on the allowlist. */
public class BadFilesReadUser {
    String read(Path path) throws IOException {
        return Files.readString(path);
    }
}
