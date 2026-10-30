package dev.bethge.shipir.proxy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProxyRouteRepository extends JpaRepository<ProxyRoute, UUID> {

    List<ProxyRoute> findByHostId(UUID hostId);

    boolean existsByCertificateId(UUID certificateId);
}
