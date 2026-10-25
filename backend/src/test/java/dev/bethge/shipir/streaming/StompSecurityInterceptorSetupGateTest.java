package dev.bethge.shipir.streaming;

import dev.bethge.shipir.common.security.SetupStatus;
import dev.bethge.shipir.common.security.SetupStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** SUBSCRIBE is refused with SETUP_REQUIRED while a setup step is pending, before any stream logic runs. */
class StompSecurityInterceptorSetupGateTest {

    private final SetupStatus setupStatus = mock(SetupStatus.class);
    private final ComposeOutputStreamService streams = mock(ComposeOutputStreamService.class);
    private final StreamAccess access = mock(StreamAccess.class);
    private final StompSecurityInterceptor interceptor = new StompSecurityInterceptor(streams,
            mock(ChatTopicAccess.class), mock(LogStreamHandler.class), mock(ExecSessionHandler.class),
            mock(DockerEventStreamService.class), mock(ContainerStatsStreamService.class), access, setupStatus);

    private Message<?> subscribe() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("s1");
        accessor.setSubscriptionId("sub1");
        accessor.setDestination("/topic/deployments/run-1");
        accessor.setUser(() -> "alice");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @ParameterizedTest
    @EnumSource(SetupStep.class)
    void subscribeIsRefusedWhileAnySetupStepIsPending(SetupStep step) {
        when(setupStatus.currentStep("alice")).thenReturn(Optional.of(step));

        assertThatThrownBy(() -> interceptor.preSend(subscribe(), null))
                .isInstanceOf(MessageDeliveryException.class).hasMessageContaining("SETUP_REQUIRED");
        verifyNoInteractions(streams, access);
    }

    @Test
    void subscribePassesTheGateOnceSetupIsDone() {
        when(setupStatus.currentStep("alice")).thenReturn(Optional.empty());
        when(access.mayFollowRun(any(), any())).thenReturn(true);

        assertThat(interceptor.preSend(subscribe(), null)).isNotNull();
    }
}
