package dev.bethge.shipir.docker.dto;

import java.time.Instant;

/** One volume. {@code composeProject} is the Compose project label (null = none). {@code created} is null when unavailable (docker-java 3.7.1 does not map the daemon's {@code CreatedAt}, so it is currently always null in practice); {@code inUse} = mounted by any container (any state). */
public record VolumeDto(String name, String driver, String mountpoint, Instant created, boolean inUse,
                        String composeProject) {

    /** A volume without a Compose project label. */
    public VolumeDto(String name, String driver, String mountpoint, Instant created, boolean inUse) {
        this(name, driver, mountpoint, created, inUse, null);
    }
}
