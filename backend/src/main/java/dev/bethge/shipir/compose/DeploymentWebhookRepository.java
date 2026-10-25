package dev.bethge.shipir.compose;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeploymentWebhookRepository extends JpaRepository<DeploymentWebhook, UUID> {

    Optional<DeploymentWebhook> findByDeploymentId(UUID deploymentId);

    Optional<DeploymentWebhook> findByTokenHash(String tokenHash);
}
