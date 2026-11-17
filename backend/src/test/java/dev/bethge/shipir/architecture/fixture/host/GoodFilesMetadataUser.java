package dev.bethge.shipir.architecture.fixture.host;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Allowed: metadata queries neither read nor change file content. */
public class GoodFilesMetadataUser {
    boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(path);
    }
}
