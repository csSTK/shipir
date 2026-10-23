package dev.bethge.shipir.git;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

interface GitCredentialRepository extends JpaRepository<GitCredential, UUID> {

    boolean existsByName(String name);

    /** Native on purpose: git/ must not depend on the compose/ deployment entity. */
    @Query(value = "SELECT count(*) FROM deployments WHERE git_credential_id = :id", nativeQuery = true)
    long countDeploymentsUsing(UUID id);
}
