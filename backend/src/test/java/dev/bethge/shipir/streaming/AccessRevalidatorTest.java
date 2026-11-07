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
                mock(ChatTopicAccess.class), mock(ContainerStatsStreamService.class));

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
                mock(ChatTopicAccess.class), mock(ContainerStatsStreamService.class));

        revalidator.onHostRemoved(new dev.bethge.shipir.common.security.HostRemovedEvent(hostId));

        verify(logs).dropHost(hostId);
        verify(project).dropHost(hostId);
        verify(events).dropHost(hostId);
    }

    @Test
    void deploymentRemovalRevalidatesProjectAndContainerLogSubscribersOfEveryUser() {
        var exec = mock(ExecSessionHandler.class);
        var logs = mock(LogStreamHandler.class);
        var project = mock(DeploymentLogStreamService.class);
        var access = mock(StreamAccess.class);
        var id = java.util.UUID.randomUUID();
        var host = java.util.UUID.randomUUID();
        org.mockito.Mockito.when(access.mayFollowProject("alice", host, "shop")).thenReturn(false);
        org.mockito.Mockito.when(access.mayFollowContainer("alice", host, "c1")).thenReturn(false);
        var revalidator = new AccessRevalidator(access, exec, logs, project, mock(DockerEventStreamService.class),
                mock(ComposeOutputStreamService.class), mock(ChatTopicSubscriptions.class), mock(ChatTopicAccess.class), mock(ContainerStatsStreamService.class));

        revalidator.onDeploymentRemoved(new dev.bethge.shipir.common.security.DeploymentRemovedEvent(id));

        verify(exec).endForDeployment(id);
        var projectCheck = org.mockito.ArgumentCaptor.forClass(java.util.function.BiPredicate.class);
        verify(project).revalidateAll(projectCheck.capture());
        org.assertj.core.api.Assertions.assertThat(projectCheck.getValue().test("alice",
                new DeploymentLogStreamService.Target(host, "shop"))).isEqualTo(false);
        var logCheck = org.mockito.ArgumentCaptor.forClass(java.util.function.BiPredicate.class);
        verify(logs).revalidateAll(logCheck.capture());
        org.assertj.core.api.Assertions.assertThat(logCheck.getValue().test("alice",
                new LogStreamHandler.Target(host, "c1"))).isEqualTo(false);
    }

    @Test
    void deploymentRemovalStillRevalidatesLogsWhenEndingConsolesFails() {
        var exec = mock(ExecSessionHandler.class);
        var logs = mock(LogStreamHandler.class);
        var project = mock(DeploymentLogStreamService.class);
        doThrow(new IllegalStateException("boom")).when(exec).endForDeployment(any());
        var revalidator = new AccessRevalidator(mock(StreamAccess.class), exec, logs, project,
                mock(DockerEventStreamService.class), mock(ComposeOutputStreamService.class),
                mock(ChatTopicSubscriptions.class), mock(ChatTopicAccess.class), mock(ContainerStatsStreamService.class));

        revalidator.onDeploymentRemoved(new dev.bethge.shipir.common.security.DeploymentRemovedEvent(
                java.util.UUID.randomUUID()));

        verify(logs).revalidateAll(any());
        verify(project).revalidateAll(any());
    }

    @Test
    void accessChangeRefreshesTheAdminFlagOfStreamSubscriptionsAndFailsClosed() {
        var logs = mock(LogStreamHandler.class);
        var project = mock(DeploymentLogStreamService.class);
        var events = mock(DockerEventStreamService.class);
        var stats = mock(ContainerStatsStreamService.class);
        var access = mock(StreamAccess.class);
        org.mockito.Mockito.when(access.roleOf("alice")).thenReturn(java.util.Optional.of(dev.bethge.shipir.auth.Role.VIEWER));
        org.mockito.Mockito.when(access.roleOf("bob")).thenThrow(new IllegalStateException("db down"));
        var revalidator = new AccessRevalidator(access, mock(ExecSessionHandler.class), logs, project, events,
                mock(ComposeOutputStreamService.class), mock(ChatTopicSubscriptions.class), mock(ChatTopicAccess.class),
                stats);

        revalidator.onAccessChanged(new UserAccessChangedEvent("alice"));
        revalidator.onAccessChanged(new UserAccessChangedEvent("bob"));

        verify(logs).refreshAdmin("alice", false);
        verify(project).refreshAdmin("alice", false);
        verify(events).refreshAdmin("alice", false);
        verify(stats).refreshAdmin("alice", false);
        verify(events).refreshAdmin("bob", false); // unknown role: treated as not ADMIN
        verify(stats).refreshAdmin("bob", false);
    }

    @Test
    void adminFlagIsRefreshedBeforeAnyRevalidation() {
        var exec = mock(ExecSessionHandler.class);
        var logs = mock(LogStreamHandler.class);
        var project = mock(DeploymentLogStreamService.class);
        var events = mock(DockerEventStreamService.class);
        var stats = mock(ContainerStatsStreamService.class);
        var runs = mock(ComposeOutputStreamService.class);
        var revalidator = new AccessRevalidator(mock(StreamAccess.class), exec, logs, project, events, runs,
                mock(ChatTopicSubscriptions.class), mock(ChatTopicAccess.class), stats);

        revalidator.onAccessChanged(new UserAccessChangedEvent("alice"));

        var order = org.mockito.Mockito.inOrder(logs, project, events, stats, exec, runs);
        order.verify(logs).refreshAdmin("alice", false);
        order.verify(project).refreshAdmin("alice", false);
        order.verify(events).refreshAdmin("alice", false);
        order.verify(stats).refreshAdmin("alice", false);
        order.verify(exec).revalidate(any(), any());
        order.verify(logs).revalidate(any(), any());
        order.verify(events).revalidate(any(), any());
        order.verify(runs).revalidate(any(), any());
    }

    @Test
    void oneFailingRefreshDoesNotSkipTheOthersOrTheRevalidations() {
        var logs = mock(LogStreamHandler.class);
        var project = mock(DeploymentLogStreamService.class);
        var events = mock(DockerEventStreamService.class);
        var stats = mock(ContainerStatsStreamService.class);
        var runs = mock(ComposeOutputStreamService.class);
        doThrow(new IllegalStateException("boom")).when(logs).refreshAdmin(any(), org.mockito.ArgumentMatchers.anyBoolean());
        var revalidator = new AccessRevalidator(mock(StreamAccess.class), mock(ExecSessionHandler.class), logs, project,
                events, runs, mock(ChatTopicSubscriptions.class), mock(ChatTopicAccess.class), stats);

        revalidator.onAccessChanged(new UserAccessChangedEvent("alice"));

        verify(project).refreshAdmin("alice", false);
        verify(events).refreshAdmin("alice", false);
        verify(stats).refreshAdmin("alice", false);
        verify(logs).revalidate(any(), any());
        verify(runs).revalidate(any(), any());
    }
}
