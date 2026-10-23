package dev.bethge.shipir.git;

import dev.bethge.shipir.git.AutoUpdateDeployments.AutoUpdateDeployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Every tick ({@code shipir.git.poll-tick}, default 60s) checks the Git deployments with auto-update whose own interval has
 * elapsed: {@code ls-remote} the ref and, if the commit differs from the last deployed one, redeploy. Off with
 * {@code shipir.git.poll-enabled=false}.
 *
 * <p>Failures are stored as an error code on the deployment and logged only when the code changes, so a permanently broken
 * repository does not flood the log every interval. One deployment failing never stops the others. A deployment with a running
 * deployment is skipped without being marked as polled, so it is retried on the next tick.
 */
@Component
@ConditionalOnProperty(name = "shipir.git.poll-enabled", matchIfMissing = true)
public class GitPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(GitPollingScheduler.class);

    private final AutoUpdateDeployments deployments;
    private final GitRepositoryService git;
    private final Clock clock;

    GitPollingScheduler(AutoUpdateDeployments deployments, GitRepositoryService git, Clock clock) {
        this.deployments = deployments;
        this.git = git;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shipir.git.poll-tick:60s}", initialDelayString = "${shipir.git.poll-tick:60s}")
    public void tick() {
        Instant now = clock.instant();
        for (AutoUpdateDeployment deployment : deployments.findEnabled()) {
            try {
                poll(deployment, now);
            } catch (RuntimeException e) {
                log.error("Git polling of deployment {} failed unexpectedly: {}", deployment.id(), e.getClass().getSimpleName());
            }
        }
    }

    private void poll(AutoUpdateDeployment deployment, Instant now) {
        if (deployment.lastPolledAt() != null
                && now.isBefore(deployment.lastPolledAt().plus(Duration.ofSeconds(deployment.intervalSeconds())))) {
            return;
        }
        try {
            String remote = git.remoteCommit(deployment.repoUrl(), deployment.ref(), deployment.credentialId());
            if (!remote.equals(deployment.lastCommitSha())
                    && deployments.redeploy(deployment.id()) == AutoUpdateDeployments.RedeployOutcome.BUSY) {
                return;
            }
            deployments.recordPoll(deployment.id(), now, null);
        } catch (GitOperationException e) {
            failed(deployment, now, e.code());
        } catch (AutoUpdateFailedException e) {
            failed(deployment, now, e.code());
        } catch (RuntimeException e) {
            failed(deployment, now, "UNEXPECTED");
        }
    }

    private void failed(AutoUpdateDeployment deployment, Instant now, String code) {
        if (!Objects.equals(code, deployment.lastPollError())) {
            log.warn("Git auto-update of deployment {} failed: {}", deployment.id(), code);
        }
        deployments.recordPoll(deployment.id(), now, code);
    }
}
