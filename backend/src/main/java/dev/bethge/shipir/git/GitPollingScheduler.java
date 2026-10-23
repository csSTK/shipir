package dev.bethge.shipir.git;

import dev.bethge.shipir.git.AutoUpdateStacks.AutoUpdateStack;
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
 * Every tick ({@code shipir.git.poll-tick}, default 60s) checks the Git stacks with auto-update whose own interval has
 * elapsed: {@code ls-remote} the ref and, if the commit differs from the last deployed one, redeploy. Off with
 * {@code shipir.git.poll-enabled=false}.
 *
 * <p>Failures are stored as an error code on the stack and logged only when the code changes, so a permanently broken
 * repository does not flood the log every interval. One stack failing never stops the others. A stack with a running
 * deployment is skipped without being marked as polled, so it is retried on the next tick.
 */
@Component
@ConditionalOnProperty(name = "shipir.git.poll-enabled", matchIfMissing = true)
public class GitPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(GitPollingScheduler.class);

    private final AutoUpdateStacks stacks;
    private final GitRepositoryService git;
    private final Clock clock;

    GitPollingScheduler(AutoUpdateStacks stacks, GitRepositoryService git, Clock clock) {
        this.stacks = stacks;
        this.git = git;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shipir.git.poll-tick:60s}", initialDelayString = "${shipir.git.poll-tick:60s}")
    public void tick() {
        Instant now = clock.instant();
        for (AutoUpdateStack stack : stacks.findEnabled()) {
            try {
                poll(stack, now);
            } catch (RuntimeException e) {
                log.error("Git polling of stack {} failed unexpectedly: {}", stack.id(), e.getClass().getSimpleName());
            }
        }
    }

    private void poll(AutoUpdateStack stack, Instant now) {
        if (stack.lastPolledAt() != null
                && now.isBefore(stack.lastPolledAt().plus(Duration.ofSeconds(stack.intervalSeconds())))) {
            return;
        }
        try {
            String remote = git.remoteCommit(stack.repoUrl(), stack.ref(), stack.credentialId());
            if (!remote.equals(stack.lastCommitSha())
                    && stacks.redeploy(stack.id()) == AutoUpdateStacks.RedeployOutcome.BUSY) {
                return;
            }
            stacks.recordPoll(stack.id(), now, null);
        } catch (GitOperationException e) {
            failed(stack, now, e.code());
        } catch (AutoUpdateFailedException e) {
            failed(stack, now, e.code());
        } catch (RuntimeException e) {
            failed(stack, now, "UNEXPECTED");
        }
    }

    private void failed(AutoUpdateStack stack, Instant now, String code) {
        if (!Objects.equals(code, stack.lastPollError())) {
            log.warn("Git auto-update of stack {} failed: {}", stack.id(), code);
        }
        stacks.recordPoll(stack.id(), now, code);
    }
}
