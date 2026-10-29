package dev.bethge.shipir.common.stream;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Port for publishing the live output of a long-running run (deploy, image pull/build/push).
 * Lets feature modules such as host/ report progress without depending on the streaming/ adapter.
 */
public interface RunOutput {

    /** Records which deployment (null for a host-level run) a run belongs to; call before {@link #start}. */
    void registerRun(String runId, UUID hostId, UUID deploymentId);

    /** Opens (or resets) the stream and returns the sink for output lines. */
    Consumer<String> start(String runId);

    /** Publishes the final status; later lines are ignored. */
    void complete(String runId, RunStatus status);
}
