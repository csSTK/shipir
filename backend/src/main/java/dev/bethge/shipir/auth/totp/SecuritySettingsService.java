package dev.bethge.shipir.auth.totp;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.auth.totp.dto.SecuritySettingsRequest;
import dev.bethge.shipir.auth.totp.dto.SecuritySettingsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Public because the audit SpEL calls {@link #get()}. */
@Service
public class SecuritySettingsService {

    private final SecuritySettingsRepository settings;
    private final UserRepository users;
    private final Clock clock;

    SecuritySettingsService(SecuritySettingsRepository settings, UserRepository users, Clock clock) {
        this.settings = settings;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SecuritySettingsResponse get() {
        return toResponse(load());
    }

    @Transactional(readOnly = true)
    public boolean require2faForAdmins() {
        return load().isRequire2faForAdmins();
    }

    @Auditable(action = "SECURITY_SETTINGS_UPDATE", targetType = "SECURITY_SETTINGS", targetId = "'1'",
            before = "get()",
            details = "{'old': {'require2faForAdmins': #before.require2faForAdmins()}, "
                    + "'new': {'require2faForAdmins': #result.require2faForAdmins()}}")
    @Transactional
    public SecuritySettingsResponse update(SecuritySettingsRequest request, String username) {
        SecuritySettings s = load();
        s.update(request.require2faForAdmins(), clock.instant(),
                users.findByUsername(username).map(u -> u.getId()).orElse(null));
        settings.flush();
        return toResponse(s);
    }

    private SecuritySettings load() {
        return settings.findById(SecuritySettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("security_settings singleton row missing"));
    }

    private static SecuritySettingsResponse toResponse(SecuritySettings s) {
        return new SecuritySettingsResponse(s.isRequire2faForAdmins(), s.getUpdatedAt());
    }
}
