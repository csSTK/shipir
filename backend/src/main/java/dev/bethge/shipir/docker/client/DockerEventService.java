package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.DockerEventDto;

import java.io.Closeable;
import java.util.UUID;
import java.util.function.Consumer;

public interface DockerEventService {

    /**
     * Streams new container, image, network and volume events of the daemon (no history) to {@code onEvent}
     * (called from a Docker I/O thread, must not block for long). Noisy actions (exec, attach, resize) are
     * dropped. Returns once the daemon answered; {@link Closeable#close()} stops the stream and is idempotent.
     * Throws {@code DockerConnectionException} with the connection codes if the daemon cannot be reached.
     */
    Closeable stream(UUID hostId, DockerEndpoint endpoint, Consumer<DockerEventDto> onEvent);

    /**
     * Like {@link #stream(UUID, DockerEndpoint, Consumer)}; {@code onEnd} is called once when the stream ends
     * (daemon gone, connection lost, or closed). Its argument is the failure, a {@code DockerConnectionException}
     * where it maps to a code, or null for a normal end.
     */
    Closeable stream(UUID hostId, DockerEndpoint endpoint, Consumer<DockerEventDto> onEvent,
                     Consumer<Throwable> onEnd);
}
