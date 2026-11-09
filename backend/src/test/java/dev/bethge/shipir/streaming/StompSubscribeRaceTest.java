package dev.bethge.shipir.streaming;

import dev.bethge.shipir.common.security.SetupStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SUBSCRIBE is check, register, check again: an access change that lands between the first check and the
 * registration is missed by {@code AccessRevalidator} (the subscription is not tracked yet), so the second check
 * catches it. After the broker has registered a chat subscription it is checked once more, because a synthetic
 * UNSUBSCRIBE of a concurrent revalidation can overtake the real SUBSCRIBE on the multi-threaded inbound channel.
 */
class StompSubscribeRaceTest {

    private final SetupStatus setupStatus = mock(SetupStatus.class);
    private final ComposeOutputStreamService streams = mock(ComposeOutputStreamService.class);
    private final StreamAccess access = mock(StreamAccess.class);
    private final ChatTopicAccess chatAccess = mock(ChatTopicAccess.class);
    private final MessageChannel inbound = mock(MessageChannel.class);
    private final ChatTopicSubscriptions chatSubscriptions = new ChatTopicSubscriptions(inbound);
    private final LogStreamHandler logs = mock(LogStreamHandler.class);
    private final DeploymentLogStreamService projectLogs = mock(DeploymentLogStreamService.class);
    private final ExecSessionHandler exec = mock(ExecSessionHandler.class);
    private final DockerEventStreamService events = mock(DockerEventStreamService.class);
    private final ContainerStatsStreamService stats = mock(ContainerStatsStreamService.class);
    private final StompSecurityInterceptor interceptor = new StompSecurityInterceptor(streams, chatAccess,
            chatSubscriptions, logs, projectLogs, exec, events, stats, access, setupStatus);
    private static final String HOST = "00000000-0000-0000-0000-000000000001";
    private static final java.util.UUID HOST_ID = java.util.UUID.fromString(HOST);

    private Message<?> subscribe(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("s1");
        accessor.setSubscriptionId("sub1");
        accessor.setDestination(destination);
        accessor.setUser(() -> "alice");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void chatAccessRevokedBetweenCheckAndRegistrationRefusesTheSubscribeAndLeavesNothingTracked() {
        when(setupStatus.currentStep("alice")).thenReturn(Optional.empty());
        when(chatAccess.maySubscribe("alice", "chat-1")).thenReturn(true, false);

        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/chat/chat-1"), null))
                .isInstanceOf(MessageDeliveryException.class);

        assertThat(chatSubscriptions.count()).isZero();
    }

    @Test
    void chatSubscribeWithUnchangedAccessIsTracked() {
        when(setupStatus.currentStep("alice")).thenReturn(Optional.empty());
        when(chatAccess.maySubscribe("alice", "chat-1")).thenReturn(true);

        assertThat(interceptor.preSend(subscribe("/topic/chat/chat-1"), null)).isNotNull();

        assertThat(chatSubscriptions.count()).isEqualTo(1);
    }

    @Test
    void chatAccessRevokedWhileTheBrokerRegistersEndsTheSubscriptionAfterwards() {
        when(chatAccess.maySubscribe("alice", "chat-1")).thenReturn(false);
        chatSubscriptions.add("alice", "chat-1", "s1", "sub1");

        interceptor.afterMessageHandled(subscribe("/topic/chat/chat-1"), null, null, null);

        assertThat(chatSubscriptions.count()).isZero();
        ArgumentCaptor<Message<?>> sent = ArgumentCaptor.forClass(Message.class);
        verify(inbound).send(sent.capture());
        SimpMessageHeaderAccessor frame = SimpMessageHeaderAccessor.wrap(sent.getValue());
        assertThat(frame.getMessageType()).isEqualTo(SimpMessageType.UNSUBSCRIBE);
        assertThat(frame.getSessionId()).isEqualTo("s1");
        assertThat(frame.getSubscriptionId()).isEqualTo("sub1");
    }

    @Test
    void revalidationUnsubscribeThatOvertookTheSubscribeIsRepeatedAfterTheBrokerRegistered() {
        // the revalidation already removed the entry and its UNSUBSCRIBE ran before the broker saw the SUBSCRIBE
        when(chatAccess.maySubscribe("alice", "chat-1")).thenReturn(false);

        interceptor.afterMessageHandled(subscribe("/topic/chat/chat-1"), null, null, null);

        verify(inbound).send(any());
    }

    @Test
    void chatSubscriptionStillAllowedIsLeftAloneAfterTheBrokerRegistered() {
        when(chatAccess.maySubscribe("alice", "chat-1")).thenReturn(true);
        chatSubscriptions.add("alice", "chat-1", "s1", "sub1");

        interceptor.afterMessageHandled(subscribe("/topic/chat/chat-1"), null, null, null);

        assertThat(chatSubscriptions.count()).isEqualTo(1);
        verify(inbound, never()).send(any());
    }

    @Test
    void throwingChatRecheckAfterTheBrokerRegisteredEndsTheSubscription() {
        when(chatAccess.maySubscribe("alice", "chat-1")).thenThrow(new IllegalStateException("db down"));
        chatSubscriptions.add("alice", "chat-1", "s1", "sub1");

        interceptor.afterMessageHandled(subscribe("/topic/chat/chat-1"), null, null, null);

        assertThat(chatSubscriptions.count()).isZero();
        verify(inbound).send(any());
    }

    @Test
    void runAccessRevokedBetweenCheckAndRegistrationDropsTheRegistration() {
        when(setupStatus.currentStep("alice")).thenReturn(Optional.empty());
        when(access.mayFollowRun(any(), any())).thenReturn(true, false);

        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/deployments/run-1"), null))
                .isInstanceOf(MessageDeliveryException.class);

        verify(streams).subscribe("run-1", "alice", "s1", "sub1");
        verify(streams).unsubscribe("s1", "sub1");
    }

    @Test
    void containerLogAccessRevokedBetweenCheckAndRegistrationDropsTheRegistration() {
        when(setupStatus.currentStep("alice")).thenReturn(Optional.empty());
        when(access.mayFollowContainer("alice", java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), "abc123"))
                .thenReturn(true, false);
        when(logs.parseOptions(any(), any())).thenReturn(new LogStreamHandler.Options(100, false));

        assertThatThrownBy(() -> interceptor.preSend(subscribe(
                "/topic/hosts/00000000-0000-0000-0000-000000000001/containers/abc123/logs"), null))
                .isInstanceOf(MessageDeliveryException.class);

        verify(logs).unsubscribe("s1", "sub1");
    }

    @Test
    void eventsAccessRevokedBetweenCheckAndRegistrationDropsTheRegistration() {
        when(setupStatus.currentStep("alice")).thenReturn(Optional.empty());
        when(access.hostFullyVisible("alice", HOST_ID)).thenReturn(true, false);

        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/hosts/" + HOST + "/events"), null))
                .isInstanceOf(MessageDeliveryException.class);

        verify(events).unsubscribe("s1", "sub1");
    }

    @Test
    void statsAccessRevokedBetweenCheckAndRegistrationDropsTheRegistration() {
        when(setupStatus.currentStep("alice")).thenReturn(Optional.empty());
        when(access.hostVisible("alice", HOST_ID)).thenReturn(true, false);

        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/hosts/" + HOST + "/stats"), null))
                .isInstanceOf(MessageDeliveryException.class);

        verify(stats).unsubscribe("s1", "sub1");
    }

    @Test
    void projectLogAccessRevokedBetweenCheckAndRegistrationDropsTheRegistration() {
        when(setupStatus.currentStep("alice")).thenReturn(Optional.empty());
        when(access.mayFollowProject("alice", HOST_ID, "web")).thenReturn(true, false);
        when(projectLogs.parseOptions(any(), any())).thenReturn(new DeploymentLogStreamService.Options(100, false));

        assertThatThrownBy(() -> interceptor.preSend(
                subscribe("/topic/hosts/" + HOST + "/deployments/web/logs"), null))
                .isInstanceOf(MessageDeliveryException.class);

        verify(projectLogs).unsubscribe("s1", "sub1");
    }

    @Test
    void consoleUserDemotedBetweenCheckAndAttachDetaches() {
        when(setupStatus.currentStep("alice")).thenReturn(Optional.empty());
        when(access.roleOf("alice")).thenReturn(Optional.of(dev.bethge.shipir.auth.Role.OPERATOR),
                Optional.of(dev.bethge.shipir.auth.Role.VIEWER));
        when(exec.attach(any(), any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/exec/abc-1"), null))
                .isInstanceOf(MessageDeliveryException.class);

        verify(exec).detach("s1", "sub1");
    }

    @Test
    void throwingRecheckFailsClosedAndDropsTheRegistration() {
        when(setupStatus.currentStep("alice")).thenReturn(Optional.empty());
        when(access.mayFollowRun(any(), any())).thenReturn(true).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/deployments/run-1"), null))
                .isInstanceOf(MessageDeliveryException.class);

        verify(streams).unsubscribe("s1", "sub1");
    }

    @Test
    void throwingChatRecheckFailsClosedAndLeavesNothingTracked() {
        when(setupStatus.currentStep("alice")).thenReturn(Optional.empty());
        when(chatAccess.maySubscribe("alice", "chat-1")).thenReturn(true).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/chat/chat-1"), null))
                .isInstanceOf(MessageDeliveryException.class);

        assertThat(chatSubscriptions.count()).isZero();
    }
}
