package dev.bethge.shipir.git;

import java.util.List;
import java.util.UUID;

/**
 * Port of {@link GitPollingScheduler} to the deployments. Defined here and implemented by {@code compose/}, because
 * {@code git/} must not depend on {@code compose/} (same pattern as {@code SetupStatus}).
 */
public interface AutoUpdateDeployments {

    /** Git deployments with auto-update switched on. */
    List<AutoUpdateDeployment> findEnabled();

    /**
     * Pull-and-redeploys the deployment as the system user.
     *
     * @return {@link RedeployOutcome#BUSY} if a deployment of the deployment is running (nothing was started)
     * @throws AutoUpdateFailedException if the redeploy was rejected (invalid compose file, key missing, ...)
     */
    RedeployOutcome redeploy(UUID deploymentId);

    /** Stores when the remote was checked and the error code of a failed check (null clears the error). */
    void recordPoll(UUID deploymentId, java.time.Instant at, String error);

    enum RedeployOutcome { STARTED, BUSY }

    /** Everything the scheduler needs of a deployment; no secrets. */
    record AutoUpdateDeployment(UUID id, String repoUrl, String ref, UUID credentialId, int intervalSeconds,
                           String lastCommitSha, java.time.Instant lastPolledAt, String lastPollError) {
    }
}
