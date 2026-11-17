package dev.bethge.shipir.architecture.fixture.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Allowed only because the fail test puts it on the allowlist. */
public class GoodAllowlistedFileUser {
    byte[] read(Path path) throws IOException {
        return Files.readAllBytes(path);
    }
}
