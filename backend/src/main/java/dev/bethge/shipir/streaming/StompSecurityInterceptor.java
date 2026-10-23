package dev.bethge.shipir.streaming;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * Inbound STOMP frame policy: CONNECT needs an authenticated handshake principal and the CSRF token (header
 * {@code X-XSRF-TOKEN} equal to the {@code XSRF-TOKEN} cookie of the handshake, see {@link CsrfHandshakeInterceptor}),
 * SUBSCRIBE only to {@code /topic/deployments/{id}} (and registers the subscription for replay), clients may not SEND.
 */
@Component
public class StompSecurityInterceptor implements ChannelInterceptor {

    static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final Pattern DEPLOYMENT_TOPIC =
            Pattern.compile("^" + Pattern.quote(ComposeOutputStreamService.TOPIC_PREFIX) + "([A-Za-z0-9-]{1,64})$");

    private final ComposeOutputStreamService streams;

    StompSecurityInterceptor(ComposeOutputStreamService streams) {
        this.streams = streams;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> checkConnect(accessor);
            case SUBSCRIBE -> {
                requireUser(accessor);
                var m = DEPLOYMENT_TOPIC.matcher(String.valueOf(accessor.getDestination()));
                if (!m.matches()) {
                    throw new MessageDeliveryException("Subscription destination not allowed");
                }
                streams.subscribe(m.group(1), accessor.getSessionId(), accessor.getSubscriptionId());
            }
            case UNSUBSCRIBE -> streams.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId());
            case SEND -> throw new MessageDeliveryException("Sending is not allowed");
            default -> {
                // DISCONNECT, heartbeats and the like need no checks
            }
        }
        return message;
    }

    private void checkConnect(StompHeaderAccessor accessor) {
        requireUser(accessor);
        var attrs = accessor.getSessionAttributes();
        String expected = attrs == null ? null : (String) attrs.get(CsrfHandshakeInterceptor.CSRF_ATTRIBUTE);
        String given = accessor.getFirstNativeHeader(CSRF_HEADER);
        if (expected == null || given == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            throw new MessageDeliveryException("CSRF token missing or invalid");
        }
    }

    private static void requireUser(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new MessageDeliveryException("Authentication required");
        }
    }

    @EventListener
    void onDisconnect(SessionDisconnectEvent event) {
        streams.unsubscribe(event.getSessionId(), null);
    }
}
