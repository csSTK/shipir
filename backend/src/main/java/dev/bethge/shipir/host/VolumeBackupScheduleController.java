package dev.bethge.shipir.host;

import dev.bethge.shipir.host.dto.VolumeBackupScheduleRequest;
import dev.bethge.shipir.host.dto.VolumeBackupScheduleResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Volume backup schedules of one environment. ADMIN/OPERATOR (matcher in SecurityConfig) plus host-level OPERATE. */
@RestController
@RequestMapping("/api/hosts/{hostId}/volume-backup-schedules")
class VolumeBackupScheduleController {

    private final VolumeBackupScheduleService service;

    VolumeBackupScheduleController(VolumeBackupScheduleService service) {
        this.service = service;
    }

    @GetMapping
    List<VolumeBackupScheduleResponse> list(@PathVariable UUID hostId) {
        return service.list(hostId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    VolumeBackupScheduleResponse create(@PathVariable UUID hostId, @RequestBody VolumeBackupScheduleRequest request) {
        return service.create(hostId, request);
    }

    @DeleteMapping("/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID hostId, @PathVariable UUID scheduleId) {
        service.delete(hostId, scheduleId);
    }
}
