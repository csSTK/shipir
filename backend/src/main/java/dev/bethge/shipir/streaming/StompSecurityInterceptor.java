package dev.bethge.shipir.streaming;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.common.security.SetupStatus;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Inbound STOMP frame policy: CONNECT needs an authenticated handshake principal and the CSRF token (header
 * {@code X-XSRF-TOKEN} equal to the {@code XSRF-TOKEN} cookie of the handshake, see {@link CsrfHandshakeInterceptor}),
 * SUBSCRIBE only to {@code /topic/deployments/{id}} (needs view access to the run's deployment; registers the subscription for replay) and
 * {@code /topic/chat/{sessionId}} (owner only, via {@link ChatTopicAccess}) and container logs
 * {@code /topic/hosts/{hostId}/containers/{id}/logs} (whole host, or a container of a managed deployment the user may view, see {@link LogStreamHandler}), project logs {@code /topic/hosts/{hostId}/deployments/{project}/logs} (view of the managed deployment or the whole host, see {@link DeploymentLogStreamService}), Docker events {@code /topic/hosts/{hostId}/events} (hosts the user fully sees, see {@link DockerEventStreamService}), container stats {@code /topic/hosts/{hostId}/stats} (visible hosts, frames filtered per user, see {@link ContainerStatsStreamService}), interactive console {@code /topic/exec/{id}} (ADMIN or OPERATOR owner, see {@link ExecSessionHandler}); clients may only SEND
 * input/resize frames of their own console to {@code /app/exec/{id}/{input|resize}}.
 * CONNECT and SUBSCRIBE are refused with SETUP_REQUIRED while the user has a pending {@link SetupStatus} step.
 */
@Component
public class StompSecurityInterceptor implements ExecutorChannelInterceptor {

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
    private final ChatTopicSubscriptions chatSubscriptions;
    private final LogStreamHandler logStreams;
    private final DeploymentLogStreamService deploymentLogs;
    private final ExecSessionHandler execSessions;
    private final DockerEventStreamService eventStreams;
    private final ContainerStatsStreamService statsStreams;
    private final StreamAccess access;
    private final SetupStatus setupStatus;

    StompSecurityInterceptor(ComposeOutputStreamService streams, ChatTopicAccess chatAccess,
                             ChatTopicSubscriptions chatSubscriptions,
                             LogStreamHandler logStreams, DeploymentLogStreamService deploymentLogs,
                             ExecSessionHandler execSessions,
                             DockerEventStreamService eventStreams, ContainerStatsStreamService statsStreams,
                             StreamAccess access, SetupStatus setupStatus) {
        this.setupStatus = setupStatus;
        this.statsStreams = statsStreams;
        this.access = access;
        this.eventStreams = eventStreams;
        this.execSessions = execSessions;
        this.streams = streams;
        this.chatAccess = chatAccess;
        this.chatSubscriptions = chatSubscriptions;
        this.logStreams = logStreams;
        this.deploymentLogs = deploymentLogs;
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
                requireSetupDone(accessor);
                String destination = String.valueOf(accessor.getDestination());
                var chat = CHAT_TOPIC.matcher(destination);
                if (chat.matches()) {
                    // live chat events go through the broker; only the session owner may follow them
                    if (!chatAccess.maySubscribe(accessor.getUser().getName(), chat.group(1))) {
                        throw new MessageDeliveryException("Subscription destination not allowed");
                    }
                    chatSubscriptions.add(accessor.getUser().getName(), chat.group(1), accessor.getSessionId(),
                            accessor.getSubscriptionId());
                    // registered now, so a change from here on is seen by AccessRevalidator; one landing before is caught here
                    recheckOrDrop(() -> chatAccess.maySubscribe(accessor.getUser().getName(), chat.group(1)),
                            () -> chatSubscriptions.remove(accessor.getSessionId(), accessor.getSubscriptionId()));
                    break;
                }
                var execTopic = EXEC_TOPIC.matcher(destination);
                if (execTopic.matches()) {
                    // console output: ADMIN or OPERATOR who owns the session, one subscription
                    String user = accessor.getUser().getName();
                    if (!mayUseConsole(user) || !execSessions.attach(user, execTopic.group(1),
                            accessor.getSessionId(), accessor.getSubscriptionId())) {
                        throw new MessageDeliveryException("Subscription destination not allowed");
                    }
                    recheckOrDrop(() -> mayUseConsole(user),
                            () -> execSessions.detach(accessor.getSessionId(), accessor.getSubscriptionId()));
                    break;
                }
                var eventHost = DockerEventStreamService.parse(destination);
                if (eventHost != null) {
                    if (!eventStreams.mayFollow(accessor.getUser().getName(), eventHost)
                            || !access.hostFullyVisible(accessor.getUser().getName(), eventHost)) {
                        throw new MessageDeliveryException("Subscription destination not allowed");
                    }
                    eventStreams.subscribe(eventHost, accessor.getUser().getName(), accessor.getSessionId(), accessor.getSubscriptionId());
                    recheckOrDrop(() -> access.hostFullyVisible(accessor.getUser().getName(), eventHost),
                            () -> eventStreams.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId()));
                    break;
                }
                var statsHost = ContainerStatsStreamService.parse(destination);
                if (statsHost != null) {
                    // visible host suffices; each frame is filtered to the containers the user may see
                    if (!access.hostVisible(accessor.getUser().getName(), statsHost)) {
                        throw new MessageDeliveryException("Subscription destination not allowed");
                    }
                    statsStreams.subscribe(statsHost, accessor.getUser().getName(), accessor.getSessionId(),
                            accessor.getSubscriptionId());
                    recheckOrDrop(() -> access.hostVisible(accessor.getUser().getName(), statsHost),
                            () -> statsStreams.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId()));
                    break;
                }
                var projectLogs = DeploymentLogStreamService.parse(destination);
                if (projectLogs != null) {
                    // managed deployment: view of that deployment; unmanaged project: the whole host
                    if (!access.mayFollowProject(accessor.getUser().getName(), projectLogs.hostId(), projectLogs.project())) {
                        throw new MessageDeliveryException("Subscription destination not allowed");
                    }
                    DeploymentLogStreamService.Options options;
                    try {
                        options = deploymentLogs.parseOptions(accessor.getFirstNativeHeader("tail"),
                                accessor.getFirstNativeHeader("timestamps"));
                    } catch (IllegalArgumentException e) {
                        throw new MessageDeliveryException("Invalid log stream option");
                    }
                    deploymentLogs.subscribe(projectLogs, options, accessor.getUser().getName(), accessor.getSessionId(),
                            accessor.getSubscriptionId());
                    recheckOrDrop(() -> access.mayFollowProject(accessor.getUser().getName(), projectLogs.hostId(), projectLogs.project()),
                            () -> deploymentLogs.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId()));
                    break;
                }
                var logTarget = LogStreamHandler.parse(destination);
                if (logTarget != null) {
                    if (!access.mayFollowContainer(accessor.getUser().getName(), logTarget.hostId(), logTarget.containerId())) {
                        throw new MessageDeliveryException("Subscription destination not allowed");
                    }
                    LogStreamHandler.Options options;
                    try {
                        options = logStreams.parseOptions(accessor.getFirstNativeHeader("tail"),
                                accessor.getFirstNativeHeader("timestamps"));
                    } catch (IllegalArgumentException e) {
                        throw new MessageDeliveryException("Invalid log stream option");
                    }
                    logStreams.subscribe(logTarget, options, accessor.getUser().getName(), accessor.getSessionId(), accessor.getSubscriptionId());
                    recheckOrDrop(() -> access.mayFollowContainer(accessor.getUser().getName(), logTarget.hostId(), logTarget.containerId()),
                            () -> logStreams.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId()));
                    break;
                }
                var m = DEPLOYMENT_TOPIC.matcher(destination);
                if (!m.matches()) {
                    throw new MessageDeliveryException("Subscription destination not allowed");
                }
                if (!access.mayFollowRun(accessor.getUser().getName(), streams.targetOf(m.group(1)))) {
                    throw new MessageDeliveryException("Subscription destination not allowed");
                }
                streams.subscribe(m.group(1), accessor.getUser().getName(), accessor.getSessionId(), accessor.getSubscriptionId());
                recheckOrDrop(() -> access.mayFollowRun(accessor.getUser().getName(), streams.targetOf(m.group(1))),
                        () -> streams.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId()));
            }
            case UNSUBSCRIBE -> {
                streams.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId());
                chatSubscriptions.remove(accessor.getSessionId(), accessor.getSubscriptionId());
                logStreams.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId());
                deploymentLogs.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId());
                eventStreams.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId());
                statsStreams.unsubscribe(accessor.getSessionId(), accessor.getSubscriptionId());
                execSessions.detach(accessor.getSessionId(), accessor.getSubscriptionId());
            }
            case SEND -> {
                requireUser(accessor);
                var send = EXEC_SEND.matcher(String.valueOf(accessor.getDestination()));
                if (!send.matches() || !mayUseConsole(accessor.getUser().getName())) {
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

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
        // Chat frames go through the simple broker, which registers a subscription only after preSend. A synthetic
        // UNSUBSCRIBE of a concurrent revalidation can overtake the real SUBSCRIBE and find nothing to remove, so
        // once the broker has handled the SUBSCRIBE the access is checked again and the subscription ended if gone
        // (idempotent: the broker ignores an UNSUBSCRIBE it does not know).
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (ex != null || accessor == null || accessor.getCommand() != StompCommand.SUBSCRIBE
                || accessor.getUser() == null) {
            return;
        }
        var chat = CHAT_TOPIC.matcher(String.valueOf(accessor.getDestination()));
        if (chat.matches() && !chatAccess.maySubscribe(accessor.getUser().getName(), chat.group(1))) {
            chatSubscriptions.end(accessor.getSessionId(), accessor.getSubscriptionId());
        }
    }

    /**
     * Second access check after a subscription was registered (see the SUBSCRIBE branches). Fails closed: if the
     * check says no or throws (e.g. database down), the registration is dropped and the SUBSCRIBE refused.
     */
    private static void recheckOrDrop(BooleanSupplier stillAllowed, Runnable drop) {
        boolean allowed;
        try {
            allowed = stillAllowed.getAsBoolean();
        } catch (RuntimeException e) {
            allowed = false;
        }
        if (!allowed) {
            drop.run();
            throw new MessageDeliveryException("Subscription destination not allowed");
        }
    }

    private void checkConnect(StompHeaderAccessor accessor) {
        requireUser(accessor);
        requireSetupDone(accessor);
        var attrs = accessor.getSessionAttributes();
        String expected = attrs == null ? null : (String) attrs.get(CsrfHandshakeInterceptor.CSRF_ATTRIBUTE);
        String given = accessor.getFirstNativeHeader(CSRF_HEADER);
        if (expected == null || given == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            throw new MessageDeliveryException("CSRF token missing or invalid");
        }
    }

    /** Same gate as the REST API ({@code SetupGateFilter}): no streams while a setup step is pending. */
    private void requireSetupDone(StompHeaderAccessor accessor) {
        if (setupStatus.currentStep(accessor.getUser().getName()).isPresent()) {
            throw new MessageDeliveryException("SETUP_REQUIRED: complete the pending setup step first");
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
        chatSubscriptions.remove(event.getSessionId(), null);
        logStreams.unsubscribe(event.getSessionId(), null);
        deploymentLogs.unsubscribe(event.getSessionId(), null);
        eventStreams.unsubscribe(event.getSessionId(), null);
        statsStreams.unsubscribe(event.getSessionId(), null);
        execSessions.detach(event.getSessionId(), null);
    }

    /**
     * ADMIN or OPERATOR by the current database role (not the authorities captured at the handshake, which go stale
     * on demotion); whether the user really owns a console (and may still hold it) is checked per session.
     */
    private boolean mayUseConsole(String username) {
        return access.roleOf(username).filter(r -> r == Role.ADMIN || r == Role.OPERATOR).isPresent();
    }
}
