package dev.bethge.shipir.host;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface HostTagRepository extends JpaRepository<HostTag, UUID> {

    List<HostTag> findByHostId(UUID hostId);

    List<HostTag> findByHostIdIn(Collection<UUID> hostIds);
}
