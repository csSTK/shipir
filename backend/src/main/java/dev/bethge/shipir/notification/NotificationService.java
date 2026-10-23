package dev.bethge.shipir.notification;

import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.host.HostStatusChangedEvent;
import dev.bethge.shipir.host.dto.HostOverviewResponse.Status;
import dev.bethge.shipir.notification.dto.NotificationPageResponse;
import dev.bethge.shipir.notification.dto.NotificationPreferenceDto;
import dev.bethge.shipir.notification.dto.NotificationResponse;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
class NotificationService {

    static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final UserRepository users;
    private final Clock clock;

    NotificationService(NotificationRepository notifications, NotificationPreferenceRepository preferences,
                        UserRepository users, Clock clock) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.users = users;
        this.clock = clock;
    }

    /** One notification per user who has not disabled the type (no preference row = enabled). */
    @EventListener
    @Transactional
    void onHostStatusChanged(HostStatusChangedEvent event) {
        NotificationType type = event.current() == Status.DOWN ? NotificationType.HOST_DOWN : NotificationType.HOST_UP;
        String message = type == NotificationType.HOST_DOWN
                ? "Environment \"%s\" is down%s".formatted(event.hostName(),
                        event.errorCode() == null ? "" : " (" + event.errorCode() + ")")
                : "Environment \"%s\" is back up".formatted(event.hostName());
        Set<UUID> disabled = Set.copyOf(preferences.findUserIdsWithDisabled(type));
        Instant now = event.occurredAt() != null ? event.occurredAt() : clock.instant();
        List<Notification> created = users.findAll().stream()
                .map(User::getId)
                .filter(id -> !disabled.contains(id))
                .map(id -> new Notification(id, type, event.hostId(), event.hostName(), message, now))
                .toList();
        notifications.saveAll(created);
    }

    @Transactional(readOnly = true)
    NotificationPageResponse list(UUID userId, boolean unreadOnly, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Notification> p = unreadOnly
                ? notifications.findByUserIdAndReadAtIsNull(userId, pr)
                : notifications.findByUserId(userId, pr);
        return new NotificationPageResponse(p.getContent().stream().map(NotificationService::toDto).toList(),
                p.getTotalElements());
    }

    @Transactional(readOnly = true)
    long unreadCount(UUID userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    /** @return false if the notification does not exist or belongs to someone else */
    @Transactional
    boolean markRead(UUID id, UUID userId) {
        return notifications.markRead(id, userId, clock.instant()) > 0;
    }

    @Transactional
    void markAllRead(UUID userId) {
        notifications.markAllRead(userId, clock.instant());
    }

    @Transactional(readOnly = true)
    List<NotificationPreferenceDto> preferences(UUID userId) {
        Map<NotificationType, Boolean> stored = preferences.findByUserId(userId).stream()
                .collect(Collectors.toMap(NotificationPreference::getType, NotificationPreference::isInAppEnabled));
        return java.util.Arrays.stream(NotificationType.values())
                .map(t -> new NotificationPreferenceDto(t, stored.getOrDefault(t, true)))
                .toList();
    }

    @Transactional
    List<NotificationPreferenceDto> updatePreferences(UUID userId, List<NotificationPreferenceDto> updates) {
        Map<NotificationType, Boolean> wanted = new EnumMap<>(NotificationType.class);
        updates.forEach(u -> wanted.put(u.type(), u.inAppEnabled()));
        Map<NotificationType, NotificationPreference> existing = preferences.findByUserId(userId).stream()
                .collect(Collectors.toMap(NotificationPreference::getType, p -> p));
        wanted.forEach((type, enabled) -> {
            NotificationPreference p = existing.get(type);
            if (p == null) {
                preferences.save(new NotificationPreference(userId, type, enabled));
            } else {
                p.setInAppEnabled(enabled);
            }
        });
        return preferences(userId);
    }

    private static NotificationResponse toDto(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getHostId(), n.getHostName(), n.getMessage(),
                n.isRead(), n.getCreatedAt());
    }
}
