package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.LogLine;

import java.io.Closeable;
import java.util.UUID;
import java.util.function.Consumer;

public interface ContainerLogService {

    /**
     * Streams the last {@code tail} lines (negative = all) and then new output of a container, in order, to
     * {@code onLine} (called from a Docker I/O thread, must not block for long). Returns once the stream is
     * open; {@link Closeable#close()} stops the Docker stream and is idempotent. Throws
     * {@code DockerConnectionException}: NOT_FOUND for an unknown container, otherwise the connection codes.
     */
    Closeable follow(UUID hostId, DockerEndpoint endpoint, String containerId, int tail, boolean timestamps,
                     Consumer<LogLine> onLine);

    /**
     * Like {@link #follow(UUID, DockerEndpoint, String, int, boolean, Consumer)}; {@code onEnd} is called once
     * when the stream ends (container stopped, closed, or failed). Its argument is the failure or null.
     */
    Closeable follow(UUID hostId, DockerEndpoint endpoint, String containerId, int tail, boolean timestamps,
                     Consumer<LogLine> onLine, Consumer<Throwable> onEnd);
}
