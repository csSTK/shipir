package dev.bethge.shipir.permission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface PermissionRepository extends JpaRepository<Permission, UUID> {

    /** Direct grants of the user only (what the per-user admin dialog edits). */
    List<Permission> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);

    List<Permission> findByTeamId(UUID teamId);

    List<Permission> findByHostId(UUID hostId);

    List<Permission> findByDeploymentId(UUID deploymentId);

    void deleteByTeamId(UUID teamId);

    /** Everything that applies to the user: own grants plus grants of all teams the user belongs to. */
    @Query("select p from Permission p where p.userId = :userId "
            + "or p.teamId in (select m.id.teamId from TeamMember m where m.id.userId = :userId)")
    List<Permission> findEffectiveByUser(@Param("userId") UUID userId);

    @Query("select p from Permission p where p.hostId = :hostId and (p.userId = :userId "
            + "or p.teamId in (select m.id.teamId from TeamMember m where m.id.userId = :userId))")
    List<Permission> findEffectiveByUserAndHost(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    @Query("select p from Permission p where p.deploymentId = :deploymentId and (p.userId = :userId "
            + "or p.teamId in (select m.id.teamId from TeamMember m where m.id.userId = :userId))")
    List<Permission> findEffectiveByUserAndDeployment(@Param("userId") UUID userId,
                                                      @Param("deploymentId") UUID deploymentId);
}
