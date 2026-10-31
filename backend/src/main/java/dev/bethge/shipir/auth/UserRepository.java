package dev.bethge.shipir.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Case-insensitive, like the unique index on lower(username) (V44): at most one user matches. */
    @Query("select u from User u where lower(u.username) = lower(:username)")
    Optional<User> findByUsername(@Param("username") String username);

    boolean existsByUsernameIgnoreCase(String username);

    /** Row lock, so concurrent code checks of one user serialize (replay protection, recovery code use). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where lower(u.username) = lower(:username)")
    Optional<User> findByUsernameForUpdate(@Param("username") String username);

    Optional<User> findByExternalIssuerAndExternalSubject(String externalIssuer, String externalSubject);

    long countByRoleAndDisabledAtIsNullAndIdNot(Role role, UUID id);
}
