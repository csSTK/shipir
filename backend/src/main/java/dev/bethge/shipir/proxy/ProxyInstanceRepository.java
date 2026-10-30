package dev.bethge.shipir.proxy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProxyInstanceRepository extends JpaRepository<ProxyInstance, UUID> {

    Optional<ProxyInstance> findByHostId(UUID hostId);
}
