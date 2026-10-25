package dev.bethge.shipir.permission;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Membership of a user in a team (V26). */
@Entity
@Table(name = "team_members")
public class TeamMember {

    @EmbeddedId
    private TeamMemberId id;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    protected TeamMember() {
    }

    TeamMember(UUID teamId, UUID userId, Instant addedAt) {
        this.id = new TeamMemberId(teamId, userId);
        this.addedAt = addedAt;
    }

    public UUID getTeamId() { return id.teamId(); }
    public UUID getUserId() { return id.userId(); }
    public Instant getAddedAt() { return addedAt; }
}
