package dev.bethge.shipir.compose;

import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.git.AutoUpdateFailedException;
import dev.bethge.shipir.git.AutoUpdateDeployments;
import dev.bethge.shipir.git.GitOperationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapter of the {@link AutoUpdateDeployments} port (defined in {@code git/}, so that {@code git/} needs no dependency on
 * {@code compose/}). A failed check is audited as {@code DEPLOYMENT_AUTO_UPDATE} FAILURE (user {@code system}) only when
 * its error code differs from the stored one, so a permanently failing deployment produces one entry, not one per interval.
 */
@Component
class DeploymentAutoUpdates implements AutoUpdateDeployments {

    private final DeploymentRepository deployments;
    private final DeploymentRunService runs;

    DeploymentAutoUpdates(DeploymentRepository deployments, DeploymentRunService runs) {
        this.deployments = deployments;
        this.runs = runs;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AutoUpdateDeployment> findEnabled() {
        return deployments.findByAutoUpdateEnabledTrue().stream()
                .filter(s -> s.getSourceType() == DeploymentSource.GIT && s.getAutoUpdateIntervalSeconds() != null)
                .map(s -> new AutoUpdateDeployment(s.getId(), s.getGitRepoUrl(), s.getGitRef(), s.getGitCredentialId(),
                        s.getAutoUpdateIntervalSeconds(), s.getLastCommitSha(), s.getLastPolledAt(),
                        s.getLastPollError()))
                .toList();
    }

    @Override
    public RedeployOutcome redeploy(UUID deploymentId) {
        try {
            runs.autoUpdate(deploymentId);
            return RedeployOutcome.STARTED;
        } catch (DeploymentException e) {
            if ("DEPLOYMENT_IN_PROGRESS".equals(e.code())) {
                return RedeployOutcome.BUSY;
            }
            throw new AutoUpdateFailedException(e.code(), e.getMessage());
        }
        // GitOperationException passes through; the scheduler maps its code
    }

    @Override
    @Transactional
    public void recordPoll(UUID deploymentId, Instant at, String error) {
        deployments.findById(deploymentId).ifPresent(s -> {
            boolean changed = error != null && !Objects.equals(error, s.getLastPollError());
            s.recordPoll(at, error);
            deployments.save(s);
            if (changed) {
                runs.recordSystemAudit("DEPLOYMENT_AUTO_UPDATE", AuditLogEntry.Outcome.FAILURE, deploymentId,
                        Map.of("error", error));
                runs.publishAutoUpdateFailed(s, error);
            }
        });
    }
}
