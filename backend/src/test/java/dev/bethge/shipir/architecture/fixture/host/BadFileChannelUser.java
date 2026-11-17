package dev.bethge.shipir.architecture.fixture.host;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Deliberate violation: opens a FileChannel directly. */
public class BadFileChannelUser {
    FileChannel open(Path path) throws IOException {
        return FileChannel.open(path, StandardOpenOption.READ);
    }
}
