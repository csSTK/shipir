package dev.bethge.shipir.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, UUID> {

    /**
     * The newest code that is unused, not expired and has attempts left. Locked ({@code FOR UPDATE}) so
     * concurrent confirms serialize per code: attempts are counted exactly and a code is used once.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from PasswordResetCode c
            where c.userId = :userId and c.usedAt is null and c.expiresAt > :now and c.failedAttempts < :maxAttempts
            order by c.createdAt desc limit 1""")
    Optional<PasswordResetCode> findLatestOpen(@Param("userId") UUID userId, @Param("now") Instant now,
                                               @Param("maxAttempts") int maxAttempts);

    /** Marks every unused code of the user as used (a new request replaces earlier ones). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordResetCode c set c.usedAt = :now where c.userId = :userId and c.usedAt is null")
    void invalidateOpenCodes(@Param("userId") UUID userId, @Param("now") Instant now);
}
