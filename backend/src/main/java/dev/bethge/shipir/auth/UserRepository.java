package dev.bethge.shipir.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    /**
     * Row-locks every enabled ADMIN (ordered by id, so two lockers never deadlock). The last-ADMIN check runs on
     * this result: Postgres re-evaluates the predicate after a wait, so an ADMIN removed by a concurrent
     * transaction that committed meanwhile is no longer returned.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u.id from User u where u.role = dev.bethge.shipir.auth.Role.ADMIN and u.disabledAt is null order by u.id")
    List<UUID> lockEnabledAdminIds();
}
