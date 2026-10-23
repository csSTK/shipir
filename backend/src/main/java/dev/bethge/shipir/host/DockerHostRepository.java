package dev.bethge.shipir.host;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DockerHostRepository extends JpaRepository<DockerHost, UUID> {

    boolean existsByName(String name);

    boolean existsByConnectionType(ConnectionType connectionType);
}
