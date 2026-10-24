package dev.bethge.shipir.docker.dto;

import java.time.Instant;

/**
 * One direct child of a directory inside a container. {@code path} is the normalised absolute path,
 * {@code mode} the permission bits (e.g. 0644 as int 420), {@code linkTarget} only set for symlinks.
 */
public record ContainerFileEntry(String name, String path, Type type, long size, Instant modified, int mode,
                                 String linkTarget) {

    public enum Type { FILE, DIRECTORY, SYMLINK, OTHER }
}
