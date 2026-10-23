package dev.bethge.shipir.git;

import java.util.List;
import java.util.UUID;

/**
 * Port of {@link GitPollingScheduler} to the stacks. Defined here and implemented by {@code compose/}, because
 * {@code git/} must not depend on {@code compose/} (same pattern as {@code SetupStatus}).
 */
public interface AutoUpdateStacks {

    /** Git stacks with auto-update switched on. */
    List<AutoUpdateStack> findEnabled();

    /**
     * Pull-and-redeploys the stack as the system user.
     *
     * @return {@link RedeployOutcome#BUSY} if a deployment of the stack is running (nothing was started)
     * @throws AutoUpdateFailedException if the redeploy was rejected (invalid compose file, key missing, ...)
     */
    RedeployOutcome redeploy(UUID stackId);

    /** Stores when the remote was checked and the error code of a failed check (null clears the error). */
    void recordPoll(UUID stackId, java.time.Instant at, String error);

    enum RedeployOutcome { STARTED, BUSY }

    /** Everything the scheduler needs of a stack; no secrets. */
    record AutoUpdateStack(UUID id, String repoUrl, String ref, UUID credentialId, int intervalSeconds,
                           String lastCommitSha, java.time.Instant lastPolledAt, String lastPollError) {
    }
}
