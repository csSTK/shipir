package dev.bethge.shipir.streaming;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * Inbound STOMP frame policy: CONNECT needs an authenticated handshake principal and the CSRF token (header
 * {@code X-XSRF-TOKEN} equal to the {@code XSRF-TOKEN} cookie of the handshake, see {@link CsrfHandshakeInterceptor}),
 * SUBSCRIBE only to {@code /topic/deployments/{id}} (registers the subscription for replay) and
 * {@code /topic/chat/{sessionId}} (owner only, via {@link ChatTopicAccess}) and container logs
 * {@code /topic/hosts/{hostId}/containers/{id}/logs} (any authenticated user, see {@link LogStreamHandler}), interactive console {@code /topic/exec/{id}} (ADMIN owner, see {@link ExecSessionHandler}); clients may only SEND
 * input/resize frames of their own console to {@code /app/exec/{id}/{input|resize}}.
 */
@Component
public class StompSecurityInterceptor implements ChannelInterceptor {

    static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final Pattern DEPLOYMENT_TOPIC =
            Pattern.compile("^" + Pattern.quote(ComposeOutputStreamService.TOPIC_PREFIX) + "([A-Za-z0-9-]{1,64})$");

    private static final Pattern CHAT_TOPIC =
            Pattern.compile("^" + Pattern.quote(ChatTopicAccess.TOPIC_PREFIX) + "([A-Za-z0-9-]{1,64})$");

    private static final Pattern EXEC_TOPIC =
            Pattern.compile("^" + Pattern.quote(ExecSessionHandler.OUTPUT_PREFIX) + "([A-Za-z0-9-]{1,64})$");
    private static final Pattern EXEC_SEND =
            Pattern.compile("^" + Pattern.quote(ExecSessionHandler.APP_PREFIX) + "([A-Za-z0-9-]{1,64})/(input|resize)$");

    private final ComposeOutputStreamService streams;
    private final ChatTopicAccess chatAccess;
    private final LogStreamHandler logStreams;
    private final ExecSessionHandler execSessions;

    StompSecurityInterceptor(ComposeOutputStreamService streams, ChatTopicAccess chatAccess,
                             LogStreamHandler logStreams, ExecSessionHandler execSessions) {
        this.execSessions = execSessions;
        this.streams = streams;
        this.chatAccess = chatAccess;
        this.logStreams = logStreams;
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
                String destination = String.valueOf(accessor.getDestination());
                var chat = CHAT_TOPIC.matcher(destination);
                if (chat.matches()) {
                    // live chat events go through the broker; only the session owner may follow them
                    if (!chatAccess.maySubscribe(accessor.getUser().getName(), chat.group(1))) {
                        throw new MessageDeliveryException("Subscription destination not allowed");
                    }
                    break;
                }
                var execTopic = EXEC_TOPIC.matcher(destination);
                if (execTopic.matches()) {
                    // console output: ADMIN who owns the session, one subscription
                    if (!isAdmin(accessor) || !execSessions.attach(accessor.getUser().getName(), execTopic.group(1),
                            accessor.getSessionId(), accessor.getSubscriptionId())) {
                        throw new MessageDeliveryException("Subscription destination not allowed");
                    }
                    break;
                }
                var logTarget = LogStreamHandler.parse(destination);
                if (logTarget != null) {
                    if (!logStreams.mayFollow(accessor.getUser().getName(), logTarget)) {
                        throw new MessageDeliveryException("Subscription destination not allowed");
                    }
                    LogStreamHandler.Options options;
                    try {
                        options = logStreams.parseOptions(accessor.getFirstNativeHeader("tail"),
                                accessor.getFirstNativeHeader("timestamps"));
                    } catch (IllegalArgumentException e) {
                        throw new MessageDeliveryException("Invalid log stream option");
                    }
                    logStreams.subscribe(logTarget, options, accessor.getSessionId(), accessor.getSubscriptionId());
                    break;
                }
                var m = DEPLOYMENT_TOPIC.matcher(destination);
                if (!m.matches()) {
                    throw new MessageDeliveryException("Subscription destination not allowed");
                }
                streams.subscribe(m.group(1), accessor.getSessionId(), accessor.getSubscriptionId());
            }
            case UNSUBSCRIBE -> {
                streams.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId());
                logStreams.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId());
                execSessions.detach(accessor.getSessionId(), accessor.getSubscriptionId());
            }
            case SEND -> {
                requireUser(accessor);
                var send = EXEC_SEND.matcher(String.valueOf(accessor.getDestination()));
                if (!send.matches() || !isAdmin(accessor)) {
                    throw new MessageDeliveryException("Sending is not allowed");
                }
                String user = accessor.getUser().getName();
                byte[] payload = message.getPayload() instanceof byte[] b ? b : null;
                boolean ok = "input".equals(send.group(2))
                        ? execSessions.input(user, send.group(1), payload)
                        : execSessions.resize(user, send.group(1), payload);
                if (!ok) {
                    throw new MessageDeliveryException("Sending is not allowed");
                }
                return null; // handled here, nothing else consumes /app/exec
            }
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
        logStreams.unsubscribe(event.getSessionId(), null);
        execSessions.detach(event.getSessionId(), null);
    }

    private static boolean isAdmin(StompHeaderAccessor accessor) {
        return accessor.getUser() instanceof Authentication auth && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
