package dev.bethge.shipir.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, NotificationPreference.Key> {

    @Query("select p from NotificationPreference p where p.id.userId = :userId")
    List<NotificationPreference> findByUserId(UUID userId);

    /** Users who explicitly disabled the type. */
    @Query("select p.id.userId from NotificationPreference p where p.id.type = :type and p.inAppEnabled = false")
    Collection<UUID> findUserIdsWithDisabled(NotificationType type);
}
