package dev.bethge.shipir.streaming;

import dev.bethge.shipir.common.security.UserAccessChangedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** One failing revalidator must not skip the remaining ones. */
class AccessRevalidatorTest {

    @Test
    void failureInOneRevalidatorDoesNotSkipTheRest() {
        var exec = mock(ExecSessionHandler.class);
        var logs = mock(LogStreamHandler.class);
        var project = mock(DeploymentLogStreamService.class);
        var events = mock(DockerEventStreamService.class);
        var runs = mock(ComposeOutputStreamService.class);
        var chat = mock(ChatTopicSubscriptions.class);
        doThrow(new IllegalStateException("boom")).when(exec).revalidate(any(), any());
        doThrow(new IllegalStateException("boom")).when(events).revalidate(any(), any());
        var revalidator = new AccessRevalidator(mock(StreamAccess.class), exec, logs, project, events, runs, chat,
                mock(ChatTopicAccess.class));

        revalidator.onAccessChanged(new UserAccessChangedEvent("alice"));

        verify(logs).revalidate(any(), any());
        verify(project).revalidate(any(), any());
        verify(runs).revalidate(any(), any());
        verify(chat).revalidate(any(), any());
    }

    @Test
    void failureInOneHostDropDoesNotSkipTheRest() {
        var exec = mock(ExecSessionHandler.class);
        var logs = mock(LogStreamHandler.class);
        var project = mock(DeploymentLogStreamService.class);
        var events = mock(DockerEventStreamService.class);
        var hostId = java.util.UUID.randomUUID();
        doThrow(new IllegalStateException("boom")).when(exec).revokeHost(any());
        var revalidator = new AccessRevalidator(mock(StreamAccess.class), exec, logs, project, events,
                mock(ComposeOutputStreamService.class), mock(ChatTopicSubscriptions.class),
                mock(ChatTopicAccess.class));

        revalidator.onHostRemoved(new dev.bethge.shipir.common.security.HostRemovedEvent(hostId));

        verify(logs).dropHost(hostId);
        verify(project).dropHost(hostId);
        verify(events).dropHost(hostId);
    }
}
