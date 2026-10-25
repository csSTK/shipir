package dev.bethge.shipir.permission;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
record TeamMemberId(@Column(name = "team_id") UUID teamId, @Column(name = "user_id") UUID userId)
        implements Serializable {
}
