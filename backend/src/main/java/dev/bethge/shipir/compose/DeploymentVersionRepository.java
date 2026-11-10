package dev.bethge.shipir.compose;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DeploymentVersionRepository extends JpaRepository<DeploymentVersion, UUID> {

    List<DeploymentVersion> findByDeploymentIdOrderByCreatedAtDesc(UUID deploymentId);

    /** Only the YAML of each version, newest first: skips the encrypted env and other columns (shipr-0qbh.79). */
    @Query("select v.yamlContent from DeploymentVersion v where v.deploymentId = :deploymentId order by v.createdAt desc")
    List<String> findYamlContentByDeploymentId(@Param("deploymentId") UUID deploymentId);
}
