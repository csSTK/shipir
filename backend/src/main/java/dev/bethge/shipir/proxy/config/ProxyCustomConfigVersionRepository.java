package dev.bethge.shipir.proxy.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ProxyCustomConfigVersionRepository extends JpaRepository<ProxyCustomConfigVersion, UUID> {

    List<ProxyCustomConfigVersion> findByProxyInstanceIdOrderByCreatedAtDescIdDesc(UUID proxyInstanceId);

    Optional<ProxyCustomConfigVersion> findByIdAndProxyInstanceId(UUID id, UUID proxyInstanceId);

    /** Deletes all but the newest {@code keep} versions of the proxy. */
    @Modifying
    @Transactional
    @Query(value = """
            DELETE FROM proxy_custom_config_versions
            WHERE proxy_instance_id = :instanceId
              AND id NOT IN (SELECT id FROM proxy_custom_config_versions WHERE proxy_instance_id = :instanceId
                             ORDER BY created_at DESC, id DESC LIMIT :keep)
            """, nativeQuery = true)
    void prune(@org.springframework.data.repository.query.Param("instanceId") UUID instanceId,
               @org.springframework.data.repository.query.Param("keep") int keep);
}
