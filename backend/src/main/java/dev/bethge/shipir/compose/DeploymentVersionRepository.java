package dev.bethge.shipir.compose;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeploymentVersionRepository extends JpaRepository<DeploymentVersion, UUID> {

    List<DeploymentVersion> findByDeploymentIdOrderByCreatedAtDesc(UUID deploymentId);
}
