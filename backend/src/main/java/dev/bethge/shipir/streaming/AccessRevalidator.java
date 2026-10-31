package dev.bethge.shipir.streaming;

import dev.bethge.shipir.common.security.DeploymentRemovedEvent;
import dev.bethge.shipir.common.security.HostRemovedEvent;
import dev.bethge.shipir.common.security.UserAccessChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Re-authorizes a user's long-lived channels after {@link UserAccessChangedEvent} (role change, disable, delete,
 * grant update, password change or reset, IdP role change). SUBSCRIBE and SEND are checked only once against the
 * handshake principal, so without this an open console or log subscription would outlive the revoked right. Runs
 * after commit, so the checks see the new database state; without a transaction it runs immediately.
 * Exec consoles of a user who may no longer open them (not an enabled ADMIN, nor an OPERATOR with OPERATE on the
 * host or the container's deployment while operator consoles are enabled) are ended; deploy output and chat subscriptions the user may no longer follow, container and project log subscriptions the user may no longer follow (same policy as at SUBSCRIBE) and event subscriptions of
 * hosts the user no longer fully sees are dropped. Stats frames are filtered per frame and need nothing here.
 */
@Component
class AccessRevalidator {

    private static final Logger log = LoggerFactory.getLogger(AccessRevalidator.class);

    private final StreamAccess access;
    private final ExecSessionHandler execSessions;
    private final LogStreamHandler logStreams;
    private final DeploymentLogStreamService projectLogStreams;
    private final DockerEventStreamService eventStreams;
    private final ComposeOutputStreamService runStreams;
    private final ChatTopicSubscriptions chatSubscriptions;
    private final ChatTopicAccess chatAccess;

    AccessRevalidator(StreamAccess access, ExecSessionHandler execSessions, LogStreamHandler logStreams,
                      DeploymentLogStreamService projectLogStreams, DockerEventStreamService eventStreams, ComposeOutputStreamService runStreams,
                      ChatTopicSubscriptions chatSubscriptions, ChatTopicAccess chatAccess) {
        this.access = access;
        this.execSessions = execSessions;
        this.logStreams = logStreams;
        this.projectLogStreams = projectLogStreams;
        this.eventStreams = eventStreams;
        this.runStreams = runStreams;
        this.chatSubscriptions = chatSubscriptions;
        this.chatAccess = chatAccess;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onDeploymentRemoved(DeploymentRemovedEvent event) {
        execSessions.endForDeployment(event.deploymentId());
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onAccessChanged(UserAccessChangedEvent event) {
        String user = event.username();
        // each revalidator is isolated: one failing must not leave the other channels of this user unchecked
        isolated("exec", () -> execSessions.revalidate(user,
                (hostId, deploymentId) -> access.mayExec(user, hostId, deploymentId, execSessions.operatorEnabled())));
        isolated("log", () -> logStreams.revalidate(user, t -> access.mayFollowContainer(user, t.hostId(), t.containerId())));
        isolated("project log", () -> projectLogStreams.revalidate(user, t -> access.mayFollowProject(user, t.hostId(), t.project())));
        isolated("event", () -> eventStreams.revalidate(user, hostId -> access.hostFullyVisible(user, hostId)));
        isolated("run output", () -> runStreams.revalidate(user, target -> access.mayFollowRun(user, target)));
        isolated("chat", () -> chatSubscriptions.revalidate(user, chatId -> chatAccess.maySubscribe(user, chatId)));
    }

    private static void isolated(String channel, Runnable revalidation) {
        try {
            revalidation.run();
        } catch (RuntimeException e) {
            log.warn("Revalidating {} subscriptions failed: {}", channel, e.getClass().getSimpleName());
        }
    }

    /** A deleted host takes its consoles and log/event subscriptions with it. */
    @TransactionalEventListener(fallbackExecution = true)
    void onHostRemoved(HostRemovedEvent event) {
        isolated("exec", () -> execSessions.revokeHost(event.hostId()));
        isolated("log", () -> logStreams.dropHost(event.hostId()));
        isolated("project log", () -> projectLogStreams.dropHost(event.hostId()));
        isolated("event", () -> eventStreams.dropHost(event.hostId()));
    }
}
