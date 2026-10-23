package dev.bethge.shipir.git;

import dev.bethge.shipir.git.AutoUpdateStacks.AutoUpdateStack;
import dev.bethge.shipir.git.AutoUpdateStacks.RedeployOutcome;
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

/** Stubbed stack port and git service, fixed clock: no Spring context, no network. */
class GitPollingSchedulerTest {

    private final Instant now = Instant.parse("2026-01-01T10:00:00Z");
    private final AutoUpdateStacks stacks = mock(AutoUpdateStacks.class);
    private final GitRepositoryService git = mock(GitRepositoryService.class);
    private final GitPollingScheduler scheduler =
            new GitPollingScheduler(stacks, git, Clock.fixed(now, ZoneOffset.UTC));
    private final List<AutoUpdateStack> enabled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(stacks.findEnabled()).thenAnswer(inv -> List.copyOf(enabled));
        when(stacks.redeploy(any())).thenReturn(RedeployOutcome.STARTED);
    }

    private AutoUpdateStack stack(String lastSha, Instant lastPolled, String lastError) {
        AutoUpdateStack s = new AutoUpdateStack(UUID.randomUUID(), "https://example.com/" + enabled.size() + ".git",
                "main", null, 300, lastSha, lastPolled, lastError);
        enabled.add(s);
        return s;
    }

    @Test
    void redeploysOnlyWhenTheRemoteCommitChanged() {
        AutoUpdateStack changed = stack("aaa", null, null);
        AutoUpdateStack same = stack("bbb", null, null);
        when(git.remoteCommit(eq(changed.repoUrl()), any(), any())).thenReturn("ccc");
        when(git.remoteCommit(eq(same.repoUrl()), any(), any())).thenReturn("bbb");

        scheduler.tick();

        verify(stacks).redeploy(changed.id());
        verify(stacks, never()).redeploy(same.id());
        verify(stacks).recordPoll(changed.id(), now, null);
        verify(stacks).recordPoll(same.id(), now, null);
    }

    @Test
    void respectsTheIntervalOfEachStack() {
        AutoUpdateStack recent = stack("aaa", now.minusSeconds(299), null);
        AutoUpdateStack due = stack("aaa", now.minusSeconds(300), null);
        when(git.remoteCommit(any(), any(), any())).thenReturn("bbb");

        scheduler.tick();

        verify(git, never()).remoteCommit(eq(recent.repoUrl()), any(), any());
        verify(stacks, never()).redeploy(recent.id());
        verify(stacks).redeploy(due.id());
    }

    @Test
    void skipsBusyStackWithoutMarkingItPolled() {
        AutoUpdateStack busy = stack("aaa", null, null);
        when(git.remoteCommit(any(), any(), any())).thenReturn("bbb");
        when(stacks.redeploy(busy.id())).thenReturn(RedeployOutcome.BUSY);

        scheduler.tick();

        verify(stacks, never()).recordPoll(any(), any(), any());
    }

    @Test
    void failureOfOneStackDoesNotStopTheOthers() {
        AutoUpdateStack broken = stack("aaa", null, null);
        AutoUpdateStack rejected = stack("aaa", null, null);
        AutoUpdateStack fine = stack("aaa", null, null);
        when(git.remoteCommit(eq(broken.repoUrl()), any(), any())).thenThrow(
                new GitOperationException(HttpStatus.BAD_GATEWAY, "GIT_UNREACHABLE", "x"));
        when(git.remoteCommit(eq(rejected.repoUrl()), any(), any())).thenReturn("bbb");
        when(stacks.redeploy(rejected.id())).thenThrow(new AutoUpdateFailedException("INVALID_COMPOSE", "x"));
        when(git.remoteCommit(eq(fine.repoUrl()), any(), any())).thenReturn("bbb");

        scheduler.tick();

        verify(stacks).recordPoll(broken.id(), now, "GIT_UNREACHABLE");
        verify(stacks).recordPoll(rejected.id(), now, "INVALID_COMPOSE");
        verify(stacks).redeploy(fine.id());
        verify(stacks).recordPoll(fine.id(), now, null);
    }

    @Test
    void unexpectedErrorIsRecordedAsUnexpected() {
        AutoUpdateStack s = stack("aaa", null, null);
        when(git.remoteCommit(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        scheduler.tick();

        verify(stacks).recordPoll(s.id(), now, "UNEXPECTED");
    }

    @Test
    void successfulCheckClearsThePreviousError() {
        AutoUpdateStack s = stack("aaa", null, "GIT_UNREACHABLE");
        when(git.remoteCommit(any(), any(), any())).thenReturn("aaa");

        scheduler.tick();

        verify(stacks).recordPoll(s.id(), now, null);
        assertThat(enabled).hasSize(1);
    }
}
