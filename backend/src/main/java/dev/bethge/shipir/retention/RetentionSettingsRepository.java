package dev.bethge.shipir.retention;

import org.springframework.data.jpa.repository.JpaRepository;

interface RetentionSettingsRepository extends JpaRepository<RetentionSettings, Short> {
}
