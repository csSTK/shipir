package dev.bethge.shipir.compose;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UptimeResultRepository extends JpaRepository<UptimeResult, UUID> {

    @Query("select r from UptimeResult r where r.checkId = :checkId order by r.checkedAt desc")
    List<UptimeResult> latest(@Param("checkId") UUID checkId, Pageable page);

    @Modifying
    @Query("delete from UptimeResult r where r.checkedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
