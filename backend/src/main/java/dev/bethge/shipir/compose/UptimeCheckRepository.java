package dev.bethge.shipir.compose;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UptimeCheckRepository extends JpaRepository<UptimeCheck, UUID> {

    List<UptimeCheck> findByDeploymentIdOrderByNameAsc(UUID deploymentId);

    long countByDeploymentId(UUID deploymentId);

    List<UptimeCheck> findByEnabledTrue();
}
