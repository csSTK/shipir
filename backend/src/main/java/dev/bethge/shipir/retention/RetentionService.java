package dev.bethge.shipir.retention;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.retention.dto.RetentionSettingsRequest;
import dev.bethge.shipir.retention.dto.RetentionSettingsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RetentionService {

    private final RetentionSettingsRepository settings;
    private final UserRepository users;
    private final Clock clock;

    RetentionService(RetentionSettingsRepository settings, UserRepository users, Clock clock) {
        this.settings = settings;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RetentionSettingsResponse get() {
        return toResponse(load());
    }

    @Auditable(action = "RETENTION_UPDATE", targetType = "RETENTION_SETTINGS", targetId = "'1'",
            before = "auditValues(get())", details = "{'old': #before, 'new': auditValues(#result)}")
    @Transactional
    public RetentionSettingsResponse update(RetentionSettingsRequest request, String username) {
        RetentionSettings s = load();
        s.update(request.auditLogDays(), request.notificationDays(), request.chatSessionDays(), request.statsDays(),
                clock.instant(),
                users.findByUsername(username).map(u -> u.getId()).orElse(null));
        settings.flush();
        return toResponse(s);
    }

    /** Audit details payload: the retention periods only. */
    public Map<String, Object> auditValues(RetentionSettingsResponse r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auditLogDays", r.auditLogDays());
        m.put("notificationDays", r.notificationDays());
        m.put("chatSessionDays", r.chatSessionDays());
        m.put("statsDays", r.statsDays());
        return m;
    }

    RetentionSettings load() {
        return settings.findById(RetentionSettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("retention_settings singleton row missing"));
    }

    private static RetentionSettingsResponse toResponse(RetentionSettings s) {
        return new RetentionSettingsResponse(s.getAuditLogDays(), s.getNotificationDays(), s.getChatSessionDays(),
                s.getStatsDays(), s.getUpdatedAt());
    }
}
