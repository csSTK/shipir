package dev.bethge.shipir.docker.dto;

import java.util.Objects;

/**
 * One file to write into a container: {@code path} is relative to the target directory (no leading slash,
 * no {@code ..}, no NUL), {@code mode} the Unix permission bits (e.g. 0644); all of 07777 is kept, including
 * setuid/setgid/sticky. A symlink that already exists in the target directory is resolved by the daemon inside
 * the container. The content array is copied on construction and on access.
 */
public record ContainerFileWrite(String path, byte[] content, int mode) {

    public ContainerFileWrite {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
