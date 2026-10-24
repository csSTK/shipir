package dev.bethge.shipir.auth.totp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Singleton row (id = 1, enforced by a CHECK constraint). */
@Entity
@Table(name = "security_settings")
class SecuritySettings {

    static final short SINGLETON_ID = 1;

    @Id
    private short id = SINGLETON_ID;

    @Column(name = "require_2fa_for_admins", nullable = false)
    private boolean require2faForAdmins;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected SecuritySettings() {
    }

    boolean isRequire2faForAdmins() {
        return require2faForAdmins;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void update(boolean require2faForAdmins, Instant now, UUID by) {
        this.require2faForAdmins = require2faForAdmins;
        this.updatedAt = now;
        this.updatedBy = by;
    }
}
