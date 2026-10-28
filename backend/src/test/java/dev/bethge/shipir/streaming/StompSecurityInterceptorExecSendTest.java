package dev.bethge.shipir.streaming;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.common.security.SetupStatus;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The console gate on SEND uses the current database role, not the authorities captured at the handshake. */
class StompSecurityInterceptorExecSendTest {

    private final SetupStatus setupStatus = mock(SetupStatus.class);
    private final StreamAccess access = mock(StreamAccess.class);
    private final ExecSessionHandler exec = mock(ExecSessionHandler.class);
    private final StompSecurityInterceptor interceptor = new StompSecurityInterceptor(
            mock(ComposeOutputStreamService.class), mock(ChatTopicAccess.class), mock(ChatTopicSubscriptions.class),
            mock(LogStreamHandler.class), mock(DeploymentLogStreamService.class), exec,
            mock(DockerEventStreamService.class), mock(ContainerStatsStreamService.class), access, setupStatus);

    /** A SEND whose principal still carries the stale handshake authority ROLE_OPERATOR. */
    private Message<?> sendAsStaleOperator() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId("s1");
        accessor.setDestination("/app/exec/abc-1/input");
        accessor.setUser(new UsernamePasswordAuthenticationToken("op", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_OPERATOR")));
        return MessageBuilder.createMessage("ls".getBytes(), accessor.getMessageHeaders());
    }

    @Test
    void userDemotedToViewerAfterTheHandshakeCannotSendToAConsole() {
        when(access.roleOf("op")).thenReturn(Optional.of(Role.VIEWER));

        assertThatThrownBy(() -> interceptor.preSend(sendAsStaleOperator(), null))
                .isInstanceOf(MessageDeliveryException.class);
        verifyNoInteractions(exec);
    }

    @Test
    void disabledOrDeletedUserCannotSendToAConsole() {
        when(access.roleOf("op")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> interceptor.preSend(sendAsStaleOperator(), null))
                .isInstanceOf(MessageDeliveryException.class);
        verifyNoInteractions(exec);
    }

    @Test
    void currentOperatorStillReachesTheConsole() {
        when(access.roleOf("op")).thenReturn(Optional.of(Role.OPERATOR));
        when(exec.input(any(), any(), any())).thenReturn(true);

        assertThat(interceptor.preSend(sendAsStaleOperator(), null)).isNull();
    }

    private Message<?> subscribeAsStaleOperator() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("s1");
        accessor.setSubscriptionId("sub1");
        accessor.setDestination("/topic/exec/abc-1");
        accessor.setUser(new UsernamePasswordAuthenticationToken("op", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_OPERATOR")));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void userDemotedToViewerAfterTheHandshakeCannotSubscribeToAConsole() {
        when(access.roleOf("op")).thenReturn(Optional.of(Role.VIEWER));

        assertThatThrownBy(() -> interceptor.preSend(subscribeAsStaleOperator(), null))
                .isInstanceOf(MessageDeliveryException.class);
        verify(exec, never()).attach(any(), any(), any(), any());
    }

    @Test
    void disabledOrDeletedUserCannotSubscribeToAConsole() {
        when(access.roleOf("op")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> interceptor.preSend(subscribeAsStaleOperator(), null))
                .isInstanceOf(MessageDeliveryException.class);
        verify(exec, never()).attach(any(), any(), any(), any());
    }
}
