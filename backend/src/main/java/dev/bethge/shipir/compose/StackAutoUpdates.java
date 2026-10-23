package dev.bethge.shipir.compose;

import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.git.AutoUpdateFailedException;
import dev.bethge.shipir.git.AutoUpdateStacks;
import dev.bethge.shipir.git.GitOperationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapter of the {@link AutoUpdateStacks} port (defined in {@code git/}, so that {@code git/} needs no dependency on
 * {@code compose/}). A failed check is audited as {@code STACK_AUTO_UPDATE} FAILURE (user {@code system}) only when
 * its error code differs from the stored one, so a permanently failing stack produces one entry, not one per interval.
 */
@Component
class StackAutoUpdates implements AutoUpdateStacks {

    private final StackRepository stacks;
    private final StackDeploymentService deployments;

    StackAutoUpdates(StackRepository stacks, StackDeploymentService deployments) {
        this.stacks = stacks;
        this.deployments = deployments;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AutoUpdateStack> findEnabled() {
        return stacks.findByAutoUpdateEnabledTrue().stream()
                .filter(s -> s.getSourceType() == StackSource.GIT && s.getAutoUpdateIntervalSeconds() != null)
                .map(s -> new AutoUpdateStack(s.getId(), s.getGitRepoUrl(), s.getGitRef(), s.getGitCredentialId(),
                        s.getAutoUpdateIntervalSeconds(), s.getLastCommitSha(), s.getLastPolledAt(),
                        s.getLastPollError()))
                .toList();
    }

    @Override
    public RedeployOutcome redeploy(UUID stackId) {
        try {
            deployments.autoUpdate(stackId);
            return RedeployOutcome.STARTED;
        } catch (StackException e) {
            if ("DEPLOYMENT_IN_PROGRESS".equals(e.code())) {
                return RedeployOutcome.BUSY;
            }
            throw new AutoUpdateFailedException(e.code(), e.getMessage());
        }
        // GitOperationException passes through; the scheduler maps its code
    }

    @Override
    @Transactional
    public void recordPoll(UUID stackId, Instant at, String error) {
        stacks.findById(stackId).ifPresent(s -> {
            boolean changed = error != null && !Objects.equals(error, s.getLastPollError());
            s.recordPoll(at, error);
            stacks.save(s);
            if (changed) {
                deployments.recordSystemAudit("STACK_AUTO_UPDATE", AuditLogEntry.Outcome.FAILURE, stackId,
                        Map.of("error", error));
            }
        });
    }
}
