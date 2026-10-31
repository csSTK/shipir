package dev.bethge.shipir.host;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.host.dto.VolumeBackupScheduleRequest;
import dev.bethge.shipir.host.dto.VolumeBackupScheduleResponse;
import dev.bethge.shipir.permission.CurrentAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Create, list and delete volume backup schedules (host-level OPERATE); the scheduler records runs via {@link #recordRun}. */
@Service
public class VolumeBackupScheduleService {

    private static final Logger log = LoggerFactory.getLogger(VolumeBackupScheduleService.class);

    static final String SCHEDULE_EXISTS = "A schedule for this volume already exists";

    private final VolumeBackupScheduleRepository schedules;
    private final DockerHostRepository hosts;
    private final UserRepository users;
    private final CurrentAccess access;
    private final Clock clock;
    private final VolumeBackupStore store;
    private final SystemManagedGuard systemManaged;

    VolumeBackupScheduleService(VolumeBackupScheduleRepository schedules, DockerHostRepository hosts, UserRepository users,
                                CurrentAccess access, Clock clock, VolumeBackupStore store,
                                SystemManagedGuard systemManaged) {
        this.systemManaged = systemManaged;
        this.schedules = schedules;
        this.hosts = hosts;
        this.users = users;
        this.access = access;
        this.clock = clock;
        this.store = store;
    }

    @Transactional(readOnly = true)
    public List<VolumeBackupScheduleResponse> list(UUID hostId) {
        requireOperate(hostId);
        Set<String> hidden = systemManaged.restrictedVolumeNames(hostId);
        return schedules.findByHostIdOrderByVolumeName(hostId).stream()
                .filter(s -> !hidden.contains(s.getVolumeName()))
                .map(VolumeBackupScheduleService::toResponse).toList();
    }

    @Auditable(action = "VOLUME_BACKUP_SCHEDULE_CREATE", targetType = "VOLUME", targetId = "#request.volumeName()",
            details = "{'intervalHours': #request.intervalHours(), 'keepCount': #request.keepCount()}")
    public VolumeBackupScheduleResponse create(UUID hostId, VolumeBackupScheduleRequest request) {
        requireOperate(hostId);
        List<String> violations = validate(request);
        if (!violations.isEmpty()) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_SCHEDULE", "Invalid backup schedule", violations);
        }
        systemManaged.requireNotSystemManagedVolume(hostId, request.volumeName());
        if (schedules.existsByHostIdAndVolumeName(hostId, request.volumeName())) {
            throw new HostException(HttpStatus.CONFLICT, "SCHEDULE_EXISTS", SCHEDULE_EXISTS);
        }
        UUID creator = users.findByUsername(currentUsername()).orElseThrow().getId();
        try {
            return toResponse(schedules.saveAndFlush(new VolumeBackupSchedule(hostId, request.volumeName(),
                    request.intervalHours(), request.keepCount(), creator, clock.instant())));
        } catch (DataIntegrityViolationException e) {
            throw new HostException(HttpStatus.CONFLICT, "SCHEDULE_EXISTS", SCHEDULE_EXISTS);
        }
    }

    @Auditable(action = "VOLUME_BACKUP_SCHEDULE_DELETE", targetType = "VOLUME_BACKUP_SCHEDULE", targetId = "#scheduleId")
    public void delete(UUID hostId, UUID scheduleId) {
        requireOperate(hostId);
        VolumeBackupSchedule schedule = schedules.findByIdAndHostId(scheduleId, hostId).orElseThrow(
                () -> new HostException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "Backup schedule not found"));
        systemManaged.requireNotSystemManagedVolume(hostId, schedule.getVolumeName());
        schedules.delete(schedule);
        // Row first, files after and best-effort: a failing file delete must not keep a schedule the user removed
        // (it would keep running). Leftover archives are logged. One schedule per (host, volume) is enforced by a
        // unique constraint, so no other schedule shares these archives.
        try {
            store.deleteAll(hostId, schedule.getVolumeName());
        } catch (RuntimeException e) {
            log.warn("Schedule {} deleted, but its stored backups of volume {} on host {} could not be removed",
                    scheduleId, schedule.getVolumeName(), hostId, e);
        }
    }

    /** Stores the outcome of a run and plans the next one; a schedule deleted meanwhile is ignored. */
    @Transactional
    public void recordRun(UUID scheduleId, boolean success) {
        schedules.findById(scheduleId).ifPresent(s -> s.recordRun(success, clock.instant()));
    }

    private static List<String> validate(VolumeBackupScheduleRequest r) {
        List<String> violations = new ArrayList<>();
        if (r == null) {
            violations.add("body is required");
            return violations;
        }
        try {
            VolumeBackupService.validateVolumeName(r.volumeName());
        } catch (HostException e) {
            violations.add("volumeName: not a valid volume name");
        }
        if (r.intervalHours() == null || r.intervalHours() < 1 || r.intervalHours() > 8760) {
            violations.add("intervalHours: must be between 1 and 8760");
        }
        if (r.keepCount() == null || r.keepCount() < 1 || r.keepCount() > 100) {
            violations.add("keepCount: must be between 1 and 100");
        }
        return violations;
    }

    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    private void requireOperate(UUID hostId) {
        if (!hosts.existsById(hostId) || !access.canView(hostId)) {
            throw new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found");
        }
        if (!access.canOperate(hostId)) {
            throw new HostException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
        }
    }

    private static VolumeBackupScheduleResponse toResponse(VolumeBackupSchedule s) {
        return new VolumeBackupScheduleResponse(s.getId(), s.getVolumeName(), s.getIntervalHours(), s.getKeepCount(),
                s.getNextRunAt(), s.getLastRunAt(), s.getLastStatus());
    }
}
