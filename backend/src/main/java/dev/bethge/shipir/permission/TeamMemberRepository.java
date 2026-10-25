package dev.bethge.shipir.permission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface TeamMemberRepository extends JpaRepository<TeamMember, TeamMemberId> {

    @Query("select m from TeamMember m where m.id.teamId = :teamId")
    List<TeamMember> findByTeam(@Param("teamId") UUID teamId);

    @Query("select m from TeamMember m where m.id.userId = :userId")
    List<TeamMember> findByUser(@Param("userId") UUID userId);

    @Query("select count(m) from TeamMember m where m.id.teamId = :teamId")
    long countByTeam(@Param("teamId") UUID teamId);

    @Modifying
    @Query("delete from TeamMember m where m.id.teamId = :teamId")
    void deleteByTeam(@Param("teamId") UUID teamId);
}
