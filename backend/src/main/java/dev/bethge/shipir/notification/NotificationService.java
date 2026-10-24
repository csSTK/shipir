package dev.bethge.shipir.notification;

import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.compose.DeploymentRunFailedEvent;
import dev.bethge.shipir.compose.GitAutoUpdateFailedEvent;
import dev.bethge.shipir.host.ContainerProblemEvent;
import dev.bethge.shipir.host.HostStatusChangedEvent;
import dev.bethge.shipir.host.dto.HostOverviewResponse.Status;
import dev.bethge.shipir.notification.dto.NotificationPageResponse;
import dev.bethge.shipir.notification.dto.NotificationPreferenceDto;
import dev.bethge.shipir.notification.dto.NotificationResponse;
import dev.bethge.shipir.permission.PermissionService;
import dev.bethge.shipir.permission.PermissionSubject;
import dev.bethge.shipir.auth.Role;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NotificationService {

    static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final UserRepository users;
    private final Clock clock;
    private final PermissionService permissions;

    NotificationService(NotificationRepository notifications, NotificationPreferenceRepository preferences,
                        UserRepository users, Clock clock, PermissionService permissions) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.users = users;
        this.clock = clock;
        this.permissions = permissions;
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

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onDeploymentRunFailed(DeploymentRunFailedEvent event) {
        notifyAllowed(NotificationDraft.of(event));
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onGitAutoUpdateFailed(GitAutoUpdateFailedEvent event) {
        notifyAllowed(NotificationDraft.of(event));
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void onContainerProblem(ContainerProblemEvent event) {
        notifyAllowed(NotificationDraft.of(event));
    }

    /**
     * One notification per enabled, opted-in user who may see the target: ADMIN always; others need a grant on the
     * deployment (host grant covers it), or for a container outside any managed deployment a grant on the whole host.
     * Own transaction, so a failure here never rolls back the publisher's transaction.
     */
    private void notifyAllowed(NotificationDraft draft) {
        Set<UUID> disabled = Set.copyOf(preferences.findUserIdsWithDisabled(draft.type()));
        Instant now = draft.occurredAt() != null ? draft.occurredAt() : clock.instant();
        List<Notification> created = users.findAll().stream()
                .filter(u -> !u.isDisabled() && !disabled.contains(u.getId()))
                .filter(u -> mayView(PermissionSubject.of(u), draft))
                .map(u -> new Notification(u.getId(), draft, now))
                .toList();
        notifications.saveAll(created);
    }

    private boolean mayView(PermissionSubject subject, NotificationDraft draft) {
        if (draft.deploymentId() != null) {
            return permissions.canView(subject, draft.hostId(), draft.deploymentId());
        }
        return permissions.hostFullyVisible(subject, draft.hostId());
    }

    @Transactional(readOnly = true)
    public NotificationPageResponse list(UUID userId, boolean unreadOnly, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Optional<Set<UUID>> visible = visibleHosts(userId);
        if (visible.isPresent() && visible.get().isEmpty()) {
            return new NotificationPageResponse(List.of(), 0);
        }
        Page<Notification> p;
        if (visible.isEmpty()) {
            p = unreadOnly ? notifications.findByUserIdAndReadAtIsNull(userId, pr) : notifications.findByUserId(userId, pr);
        } else {
            p = unreadOnly
                    ? notifications.findByUserIdAndHostIdInAndReadAtIsNull(userId, visible.get(), pr)
                    : notifications.findByUserIdAndHostIdIn(userId, visible.get(), pr);
        }
        return new NotificationPageResponse(p.getContent().stream().map(NotificationService::toDto).toList(),
                p.getTotalElements());
    }

    @Transactional(readOnly = true)
    long unreadCount(UUID userId) {
        Optional<Set<UUID>> visible = visibleHosts(userId);
        if (visible.isEmpty()) {
            return notifications.countByUserIdAndReadAtIsNull(userId);
        }
        return visible.get().isEmpty() ? 0
                : notifications.countByUserIdAndHostIdInAndReadAtIsNull(userId, visible.get());
    }

    /**
     * Hosts whose notifications the user may see; empty = no restriction (ADMIN). Notifications of deleted hosts
     * (no host id) and of hosts without a grant are hidden from everyone else.
     */
    private Optional<Set<UUID>> visibleHosts(UUID userId) {
        User user = users.findById(userId).orElseThrow();
        if (user.getRole() == Role.ADMIN) {
            return Optional.empty();
        }
        return Optional.of(permissions.visibleHostIds(PermissionSubject.of(user)));
    }

    /** @return false if the notification does not exist or belongs to someone else */
    @Transactional
    public boolean markRead(UUID id, UUID userId) {
        Optional<Set<UUID>> visible = visibleHosts(userId);
        if (visible.isPresent()) {
            boolean hidden = notifications.findById(id)
                    .map(n -> n.getHostId() == null || !visible.get().contains(n.getHostId())).orElse(false);
            if (hidden) {
                return false;
            }
        }
        return notifications.markRead(id, userId, clock.instant()) > 0;
    }

    @Transactional
    public void markAllRead(UUID userId) {
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
        return new NotificationResponse(n.getId(), n.getType(), n.getHostId(), n.getHostName(),
                n.getDeploymentId(), n.getDeploymentName(), n.getContainerId(), n.getContainerName(), n.getMessage(),
                n.isRead(), n.getCreatedAt());
    }
}
