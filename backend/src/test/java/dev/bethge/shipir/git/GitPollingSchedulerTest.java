package dev.bethge.shipir.git;

import dev.bethge.shipir.git.AutoUpdateDeployments.AutoUpdateDeployment;
import dev.bethge.shipir.git.AutoUpdateDeployments.RedeployOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Stubbed deployment port and git service, fixed clock: no Spring context, no network. */
class GitPollingSchedulerTest {

    private final Instant now = Instant.parse("2026-01-01T10:00:00Z");
    private final AutoUpdateDeployments deployments = mock(AutoUpdateDeployments.class);
    private final GitRepositoryService git = mock(GitRepositoryService.class);
    private final GitPollingScheduler scheduler =
            new GitPollingScheduler(deployments, git, Clock.fixed(now, ZoneOffset.UTC));
    private final List<AutoUpdateDeployment> enabled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(deployments.findEnabled()).thenAnswer(inv -> List.copyOf(enabled));
        when(deployments.redeploy(any())).thenReturn(RedeployOutcome.STARTED);
    }

    private AutoUpdateDeployment deployment(String lastSha, Instant lastPolled, String lastError) {
        AutoUpdateDeployment s = new AutoUpdateDeployment(UUID.randomUUID(), "https://example.com/" + enabled.size() + ".git",
                "main", null, 300, lastSha, lastPolled, lastError);
        enabled.add(s);
        return s;
    }

    @Test
    void pollsAndRedeploysInsideTheSystemScope() {
        List<Boolean> scopeDuringRedeploy = new ArrayList<>();
        when(deployments.redeploy(any())).thenAnswer(inv -> {
            scopeDuringRedeploy.add(dev.bethge.shipir.common.security.SystemScope.active());
            return RedeployOutcome.STARTED;
        });
        deployment("aaa", null, null);
        when(git.remoteCommit(any(), any(), any())).thenReturn("bbb");

        scheduler.tick();

        assertThat(scopeDuringRedeploy).containsExactly(true);
        assertThat(dev.bethge.shipir.common.security.SystemScope.active()).isFalse();
    }

    @Test
    void redeploysOnlyWhenTheRemoteCommitChanged() {
        AutoUpdateDeployment changed = deployment("aaa", null, null);
        AutoUpdateDeployment same = deployment("bbb", null, null);
        when(git.remoteCommit(eq(changed.repoUrl()), any(), any())).thenReturn("ccc");
        when(git.remoteCommit(eq(same.repoUrl()), any(), any())).thenReturn("bbb");

        scheduler.tick();

        verify(deployments).redeploy(changed.id());
        verify(deployments, never()).redeploy(same.id());
        verify(deployments).recordPoll(changed.id(), now, null);
        verify(deployments).recordPoll(same.id(), now, null);
    }

    @Test
    void respectsTheIntervalOfEachDeployment() {
        AutoUpdateDeployment recent = deployment("aaa", now.minusSeconds(299), null);
        AutoUpdateDeployment due = deployment("aaa", now.minusSeconds(300), null);
        when(git.remoteCommit(any(), any(), any())).thenReturn("bbb");

        scheduler.tick();

        verify(git, never()).remoteCommit(eq(recent.repoUrl()), any(), any());
        verify(deployments, never()).redeploy(recent.id());
        verify(deployments).redeploy(due.id());
    }

    @Test
    void skipsBusyDeploymentWithoutMarkingItPolled() {
        AutoUpdateDeployment busy = deployment("aaa", null, null);
        when(git.remoteCommit(any(), any(), any())).thenReturn("bbb");
        when(deployments.redeploy(busy.id())).thenReturn(RedeployOutcome.BUSY);

        scheduler.tick();

        verify(deployments, never()).recordPoll(any(), any(), any());
    }

    @Test
    void failureOfOneDeploymentDoesNotStopTheOthers() {
        AutoUpdateDeployment broken = deployment("aaa", null, null);
        AutoUpdateDeployment rejected = deployment("aaa", null, null);
        AutoUpdateDeployment fine = deployment("aaa", null, null);
        when(git.remoteCommit(eq(broken.repoUrl()), any(), any())).thenThrow(
                new GitOperationException(HttpStatus.BAD_GATEWAY, "GIT_UNREACHABLE", "x"));
        when(git.remoteCommit(eq(rejected.repoUrl()), any(), any())).thenReturn("bbb");
        when(deployments.redeploy(rejected.id())).thenThrow(new AutoUpdateFailedException("INVALID_COMPOSE", "x"));
        when(git.remoteCommit(eq(fine.repoUrl()), any(), any())).thenReturn("bbb");

        scheduler.tick();

        verify(deployments).recordPoll(broken.id(), now, "GIT_UNREACHABLE");
        verify(deployments).recordPoll(rejected.id(), now, "INVALID_COMPOSE");
        verify(deployments).redeploy(fine.id());
        verify(deployments).recordPoll(fine.id(), now, null);
    }

    @Test
    void unexpectedErrorIsRecordedAsUnexpected() {
        AutoUpdateDeployment s = deployment("aaa", null, null);
        when(git.remoteCommit(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        scheduler.tick();

        verify(deployments).recordPoll(s.id(), now, "UNEXPECTED");
    }

    @Test
    void successfulCheckClearsThePreviousError() {
        AutoUpdateDeployment s = deployment("aaa", null, "GIT_UNREACHABLE");
        when(git.remoteCommit(any(), any(), any())).thenReturn("aaa");

        scheduler.tick();

        verify(deployments).recordPoll(s.id(), now, null);
        assertThat(enabled).hasSize(1);
    }
}
