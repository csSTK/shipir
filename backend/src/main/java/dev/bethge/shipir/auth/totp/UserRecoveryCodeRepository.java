package dev.bethge.shipir.auth.totp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface UserRecoveryCodeRepository extends JpaRepository<UserRecoveryCode, UUID> {

    @Query("select c from UserRecoveryCode c where c.userId = :userId and c.codeHash = :hash and c.usedAt is null")
    Optional<UserRecoveryCode> findUnused(@Param("userId") UUID userId, @Param("hash") String hash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from UserRecoveryCode c where c.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
