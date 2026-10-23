package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public interface ExecService {

    /**
     * Starts {@code cmd} in a running container with a TTY and stdin attached. Terminal output (stdout and
     * stderr are merged by the TTY) goes to {@code output} from a Docker I/O thread, which must not block for
     * long. Returns once the session is open. Throws {@code DockerConnectionException}: NOT_FOUND for an
     * unknown container, INVALID_STATE when it is not running, otherwise the connection codes.
     */
    ExecHandle open(UUID hostId, DockerEndpoint endpoint, String containerId, List<String> cmd, int cols, int rows,
                    Consumer<byte[]> output);

    /** Like {@link #open}; {@code onEnd} is called once when the session ends (process exited, closed or failed) with the failure or null. */
    ExecHandle open(UUID hostId, DockerEndpoint endpoint, String containerId, List<String> cmd, int cols, int rows,
                    Consumer<byte[]> output, Consumer<Throwable> onEnd);
}
