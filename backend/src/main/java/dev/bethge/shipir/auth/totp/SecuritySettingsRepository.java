package dev.bethge.shipir.auth.totp;

import org.springframework.data.jpa.repository.JpaRepository;

interface SecuritySettingsRepository extends JpaRepository<SecuritySettings, Short> {
}
