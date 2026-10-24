package dev.bethge.shipir.ai.settings;

import org.springframework.data.jpa.repository.JpaRepository;

interface AiProviderConfigRepository extends JpaRepository<AiProviderConfig, AiProvider> {
}
