package dev.bethge.shipir.compose;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    List<Deployment> findByHostIdOrderByNameAsc(UUID hostId);

    List<Deployment> findByAutoUpdateEnabledTrue();

    List<Deployment> findByImageAutoUpdateTrue();

    /** Targeted update, so the long-running worker never overwrites columns another request changed meanwhile. */
    @Transactional
    @Modifying
    @Query("update Deployment d set d.imageUpdateLastAttemptAt = :at, d.imageUpdateLastResult = :result where d.id = :id")
    int recordImageUpdateAttempt(@Param("id") UUID id, @Param("at") Instant at, @Param("result") String result);

    Optional<Deployment> findByIdAndHostId(UUID id, UUID hostId);
}
