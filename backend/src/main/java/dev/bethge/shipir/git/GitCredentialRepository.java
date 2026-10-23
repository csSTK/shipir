package dev.bethge.shipir.git;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

interface GitCredentialRepository extends JpaRepository<GitCredential, UUID> {

    boolean existsByName(String name);

    /** Native on purpose: git/ must not depend on the compose/ stack entity. */
    @Query(value = "SELECT count(*) FROM stacks WHERE git_credential_id = :id", nativeQuery = true)
    long countStacksUsing(UUID id);
}
