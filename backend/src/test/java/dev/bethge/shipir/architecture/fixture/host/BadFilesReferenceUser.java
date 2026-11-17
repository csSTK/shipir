package dev.bethge.shipir.architecture.fixture.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deliberate violation: takes Files.readString as a method reference, which a call-only rule misses. */
public class BadFilesReferenceUser {
    interface IoFunction {
        String apply(Path path) throws IOException;
    }

    IoFunction reader = Files::readString;
}
