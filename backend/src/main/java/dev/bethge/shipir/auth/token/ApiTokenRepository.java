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

    long countByUserId(UUID userId);

    @Transactional
    @Modifying
    @Query("delete from ApiToken t where t.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);

    /** Row lock on the user (NO KEY UPDATE: the REQUIRES_NEW audit insert needs a key-share lock on the same row), so concurrent token creations of one user are counted one after the other. */
    @Query(value = "SELECT id FROM users WHERE id = :userId FOR NO KEY UPDATE", nativeQuery = true)
    List<UUID> lockUser(@Param("userId") UUID userId);

    /** Stamps the use unless it was stamped after {@code threshold} already; one guarded statement, no read-modify-write. */
    @Transactional
    @Modifying
    @Query("update ApiToken t set t.lastUsedAt = :now where t.id = :id and (t.lastUsedAt is null or t.lastUsedAt < :threshold)")
    int touch(@Param("id") UUID id, @Param("now") Instant now, @Param("threshold") Instant threshold);
}
