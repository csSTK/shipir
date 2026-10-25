package dev.bethge.shipir.host;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VolumeBackupScheduleRepository extends JpaRepository<VolumeBackupSchedule, UUID> {

    List<VolumeBackupSchedule> findByHostIdOrderByVolumeName(UUID hostId);

    Optional<VolumeBackupSchedule> findByIdAndHostId(UUID id, UUID hostId);

    boolean existsByHostIdAndVolumeName(UUID hostId, String volumeName);

    List<VolumeBackupSchedule> findByNextRunAtLessThanEqualOrderByNextRunAt(Instant now);
}
