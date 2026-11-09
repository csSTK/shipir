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
    private final ContainerStatsStreamService statsStreams;

    AccessRevalidator(StreamAccess access, ExecSessionHandler execSessions, LogStreamHandler logStreams,
                      DeploymentLogStreamService projectLogStreams, DockerEventStreamService eventStreams, ComposeOutputStreamService runStreams,
                      ChatTopicSubscriptions chatSubscriptions, ChatTopicAccess chatAccess,
                      ContainerStatsStreamService statsStreams) {
        this.access = access;
        this.execSessions = execSessions;
        this.logStreams = logStreams;
        this.projectLogStreams = projectLogStreams;
        this.eventStreams = eventStreams;
        this.runStreams = runStreams;
        this.chatSubscriptions = chatSubscriptions;
        this.chatAccess = chatAccess;
        this.statsStreams = statsStreams;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onDeploymentRemoved(DeploymentRemovedEvent event) {
        isolated("exec", () -> execSessions.endForDeployment(event.deploymentId()));
        // the grants went with the deployment without a UserAccessChangedEvent; the event names no host or project, so
        // every log subscriber is asked again with the same policy as at SUBSCRIBE (cheap, deletions are rare)
        isolated("log", () -> logStreams.revalidateAll((user, t) -> access.mayFollowContainer(user, t.hostId(), t.containerId())));
        isolated("project log", () -> projectLogStreams.revalidateAll((user, t) -> access.mayFollowProject(user, t.hostId(), t.project())));
        isolated("run output", () -> runStreams.revalidateAll((user, target) -> access.mayFollowRun(user, target)));
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onAccessChanged(UserAccessChangedEvent event) {
        String user = event.username();
        // first, so a demoted ADMIN stops getting ADMIN-only frames even if a revalidation below fails
        boolean admin = isAdmin(user);
        isolated("log viewer role", () -> logStreams.refreshAdmin(user, admin));
        isolated("project log viewer role", () -> projectLogStreams.refreshAdmin(user, admin));
        isolated("event viewer role", () -> eventStreams.refreshAdmin(user, admin));
        isolated("stats viewer role", () -> statsStreams.refreshAdmin(user, admin));
        // each revalidator is isolated: one failing must not leave the other channels of this user unchecked
        isolated("exec", () -> execSessions.revalidate(user,
                (hostId, deploymentId) -> access.mayExec(user, hostId, deploymentId, execSessions.operatorEnabled())));
        isolated("log", () -> logStreams.revalidate(user, t -> access.mayFollowContainer(user, t.hostId(), t.containerId())));
        isolated("project log", () -> projectLogStreams.revalidate(user, t -> access.mayFollowProject(user, t.hostId(), t.project())));
        isolated("event", () -> eventStreams.revalidate(user, hostId -> access.hostFullyVisible(user, hostId)));
        isolated("run output", () -> runStreams.revalidate(user, target -> access.mayFollowRun(user, target)));
        isolated("chat", () -> chatSubscriptions.revalidate(user, chatId -> chatAccess.maySubscribe(user, chatId)));
    }

    /** True only for an enabled ADMIN; fails closed (not ADMIN) when the role cannot be read. */
    private boolean isAdmin(String user) {
        try {
            return access.roleOf(user).filter(r -> r == dev.bethge.shipir.auth.Role.ADMIN).isPresent();
        } catch (RuntimeException e) {
            log.warn("Reading the role of a user for stream frames failed: {}", e.getClass().getSimpleName());
            return false;
        }
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
