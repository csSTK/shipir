package dev.bethge.shipir.auth.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiTokenRepository extends JpaRepository<ApiToken, UUID> {

    Optional<ApiToken> findByTokenHash(String tokenHash);

    List<ApiToken> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<ApiToken> findByIdAndUserId(UUID id, UUID userId);

    /** Stamps the use unless it was stamped after {@code threshold} already; one guarded statement, no read-modify-write. */
    @Transactional
    @Modifying
    @Query("update ApiToken t set t.lastUsedAt = :now where t.id = :id and (t.lastUsedAt is null or t.lastUsedAt < :threshold)")
    int touch(@Param("id") UUID id, @Param("now") Instant now, @Param("threshold") Instant threshold);
}
