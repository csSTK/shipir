package dev.bethge.shipir.compose;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    List<Deployment> findByHostIdOrderByNameAsc(UUID hostId);

    List<Deployment> findByAutoUpdateEnabledTrue();

    Optional<Deployment> findByIdAndHostId(UUID id, UUID hostId);
}
