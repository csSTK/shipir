package dev.bethge.shipir.compose;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StackRepository extends JpaRepository<Stack, UUID> {

    List<Stack> findByHostIdOrderByNameAsc(UUID hostId);

    List<Stack> findByAutoUpdateEnabledTrue();

    Optional<Stack> findByIdAndHostId(UUID id, UUID hostId);
}
