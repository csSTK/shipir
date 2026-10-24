package dev.bethge.shipir.permission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PermissionRepository extends JpaRepository<Permission, UUID> {

    List<Permission> findByUserId(UUID userId);

    Optional<Permission> findByUserIdAndHostId(UUID userId, UUID hostId);

    Optional<Permission> findByUserIdAndDeploymentId(UUID userId, UUID deploymentId);
}
