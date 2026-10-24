package dev.bethge.shipir.auth.oidc;

import org.springframework.data.jpa.repository.JpaRepository;

interface OidcSettingsRepository extends JpaRepository<OidcSettings, Short> {
}
