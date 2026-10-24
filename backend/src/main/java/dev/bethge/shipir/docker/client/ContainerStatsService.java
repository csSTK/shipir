package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.ContainerStatsDto;

import java.io.Closeable;
import java.util.UUID;
import java.util.function.Consumer;

public interface ContainerStatsService {

    /**
     * Streams one sample per second to {@code onSample} (called from a Docker I/O thread, must not block for
     * long). Returns once the stream is open; {@link Closeable#close()} stops it and is idempotent. Throws
     * {@code DockerConnectionException}: NOT_FOUND for an unknown container, otherwise the connection codes.
     */
    Closeable stream(UUID hostId, DockerEndpoint endpoint, String containerId, Consumer<ContainerStatsDto> onSample);

    /** Like {@link #stream(UUID, DockerEndpoint, String, Consumer)}; {@code onEnd} runs once with the failure or null. */
    Closeable stream(UUID hostId, DockerEndpoint endpoint, String containerId, Consumer<ContainerStatsDto> onSample,
                     Consumer<Throwable> onEnd);

    /** One sample. Throws {@code DockerConnectionException}, NOT_FOUND if unknown. */
    ContainerStatsDto snapshot(UUID hostId, DockerEndpoint endpoint, String containerId);
}
