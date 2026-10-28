package dev.bethge.shipir.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserId(UUID userId, Pageable pageable);

    Page<Notification> findByUserIdAndReadAtIsNull(UUID userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    /**
     * Notifications the user may see: everything of {@code fullHosts}; on {@code partialHosts} (deployment grants
     * only) host events and the ones of {@code deploymentIds}. Collections must not be empty.
     */
    String VISIBLE = "n.userId = :userId and (n.hostId in :fullHosts or (n.hostId in :partialHosts and "
            + "(n.type in :hostTypes or n.deploymentId in :deploymentIds))) and (:unreadOnly = false or n.readAt is null)";

    @Query("select n from Notification n where " + VISIBLE)
    Page<Notification> findVisible(UUID userId, Collection<UUID> fullHosts, Collection<UUID> partialHosts,
                                   Collection<NotificationType> hostTypes, Collection<UUID> deploymentIds,
                                   boolean unreadOnly, Pageable pageable);

    @Query("select count(n) from Notification n where " + VISIBLE + " and n.readAt is null")
    long countVisibleUnread(UUID userId, Collection<UUID> fullHosts, Collection<UUID> partialHosts,
                            Collection<NotificationType> hostTypes, Collection<UUID> deploymentIds,
                            boolean unreadOnly);

    /** Idempotent: an already read notification keeps its original read time. Returns 1 if owned by the user. */
    @Modifying
    @Query("update Notification n set n.readAt = coalesce(n.readAt, :now) where n.id = :id and n.userId = :userId")
    int markRead(UUID id, UUID userId, Instant now);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(UUID userId, Instant now);

    /** Marks only the unread notifications within the visible scope (same rules as {@link #VISIBLE}). */
    @Modifying
    @Query("update Notification n set n.readAt = :now where n.readAt is null and n.userId = :userId and "
            + "(n.hostId in :fullHosts or (n.hostId in :partialHosts and "
            + "(n.type in :hostTypes or n.deploymentId in :deploymentIds)))")
    int markVisibleRead(UUID userId, Instant now, Collection<UUID> fullHosts, Collection<UUID> partialHosts,
                        Collection<NotificationType> hostTypes, Collection<UUID> deploymentIds);

    /** Bulk delete for the retention purge; returns the number of deleted rows. */
    @Modifying
    @Query("delete from Notification n where n.createdAt < :cutoff")
    int deleteByCreatedAtBefore(Instant cutoff);
}
