package dev.bethge.shipir.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface NotificationWebhookRepository extends JpaRepository<NotificationWebhook, UUID> {

    boolean existsByName(String name);

    List<NotificationWebhook> findByEnabledTrue();

    @Transactional
    @Modifying
    @Query("update NotificationWebhook w set w.lastDeliveryAt = :at, w.lastDeliveryStatus = :status where w.id = :id")
    int recordDelivery(UUID id, Instant at, String status);
}
