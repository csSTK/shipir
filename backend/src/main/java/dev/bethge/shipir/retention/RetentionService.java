package dev.bethge.shipir.retention;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.retention.dto.RetentionSettingsRequest;
import dev.bethge.shipir.retention.dto.RetentionSettingsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
class RetentionService {

    private final RetentionSettingsRepository settings;
    private final UserRepository users;
    private final Clock clock;

    RetentionService(RetentionSettingsRepository settings, UserRepository users, Clock clock) {
        this.settings = settings;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    RetentionSettingsResponse get() {
        return toResponse(load());
    }

    @Auditable(action = "RETENTION_UPDATE", targetType = "RETENTION_SETTINGS", targetId = "'1'")
    @Transactional
    RetentionSettingsResponse update(RetentionSettingsRequest request, String username) {
        RetentionSettings s = load();
        s.update(request.auditLogDays(), request.notificationDays(), clock.instant(),
                users.findByUsername(username).map(u -> u.getId()).orElse(null));
        settings.flush();
        return toResponse(s);
    }

    RetentionSettings load() {
        return settings.findById(RetentionSettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("retention_settings singleton row missing"));
    }

    private static RetentionSettingsResponse toResponse(RetentionSettings s) {
        return new RetentionSettingsResponse(s.getAuditLogDays(), s.getNotificationDays(), s.getUpdatedAt());
    }
}
