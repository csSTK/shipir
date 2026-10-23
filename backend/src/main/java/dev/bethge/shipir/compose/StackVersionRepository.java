package dev.bethge.shipir.compose;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StackVersionRepository extends JpaRepository<StackVersion, UUID> {
}
