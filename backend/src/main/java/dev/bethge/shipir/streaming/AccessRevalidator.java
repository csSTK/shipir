package dev.bethge.shipir.streaming;

import dev.bethge.shipir.common.security.UserAccessChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Re-authorizes a user's long-lived channels after {@link UserAccessChangedEvent} (role change, disable, delete,
 * grant update, password change or reset, IdP role change). SUBSCRIBE and SEND are checked only once against the
 * handshake principal, so without this an open console or log subscription would outlive the revoked right. Runs
 * after commit, so the checks see the new database state; without a transaction it runs immediately.
 * Exec consoles of a user who is no longer an enabled ADMIN are ended; log and event subscriptions of hosts the
 * user no longer fully sees are dropped. Stats frames are filtered per frame and need nothing here.
 */
@Component
class AccessRevalidator {

    private final StreamAccess access;
    private final ExecSessionHandler execSessions;
    private final LogStreamHandler logStreams;
    private final DockerEventStreamService eventStreams;

    AccessRevalidator(StreamAccess access, ExecSessionHandler execSessions, LogStreamHandler logStreams,
                      DockerEventStreamService eventStreams) {
        this.access = access;
        this.execSessions = execSessions;
        this.logStreams = logStreams;
        this.eventStreams = eventStreams;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onAccessChanged(UserAccessChangedEvent event) {
        String user = event.username();
        if (!access.isAdmin(user)) {
            execSessions.revoke(user);
        }
        logStreams.revalidate(user, hostId -> access.hostFullyVisible(user, hostId));
        eventStreams.revalidate(user, hostId -> access.hostFullyVisible(user, hostId));
    }
}
