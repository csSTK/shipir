package dev.bethge.shipir.host;

import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.permission.CurrentAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting a schedule: the row is deleted first, the archives afterwards and best-effort (a failing file delete is
 * logged as WARN and does not undo or fail the delete). One schedule per (host, volume) is enforced by a unique
 * constraint, so the archives of that volume belong to exactly this schedule.
 */
class VolumeBackupScheduleServiceDeleteTest {

    private final VolumeBackupScheduleRepository schedules = mock(VolumeBackupScheduleRepository.class);
    private final DockerHostRepository hosts = mock(DockerHostRepository.class);
    private final CurrentAccess access = mock(CurrentAccess.class);
    private final VolumeBackupStore store = mock(VolumeBackupStore.class);
    private final UUID hostId = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private VolumeBackupScheduleService service;
    private VolumeBackupSchedule schedule;

    @BeforeEach
    void setUp() {
        service = new VolumeBackupScheduleService(schedules, hosts, mock(UserRepository.class), access, Clock.systemUTC(), store);
        when(hosts.existsById(hostId)).thenReturn(true);
        when(access.canView(hostId)).thenReturn(true);
        when(access.canOperate(hostId)).thenReturn(true);
        schedule = new VolumeBackupSchedule(hostId, "data", 24, 7, UUID.randomUUID(), java.time.Instant.now());
        when(schedules.findByIdAndHostId(id, hostId)).thenReturn(Optional.of(schedule));
    }

    @Test
    void deletesRowThenStoredArchivesOfThatVolume() {
        service.delete(hostId, id);

        var order = inOrder(schedules, store);
        order.verify(schedules).delete(schedule);
        order.verify(store).deleteAll(hostId, "data");
    }

    @Test
    void failingFileDeletionDoesNotFailOrUndoTheScheduleDelete() {
        when(store.deleteAll(any(), anyString())).thenThrow(new HostException(
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "BACKUP_STORE_FAILED", "x"));

        service.delete(hostId, id);

        verify(schedules).delete(schedule);
    }

    @Test
    void unknownScheduleDeletesNoFiles() {
        UUID unknown = UUID.randomUUID();
        when(schedules.findByIdAndHostId(unknown, hostId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(hostId, unknown)).isInstanceOf(HostException.class);

        verify(store, never()).deleteAll(any(), anyString());
    }

    @Test
    void failingRowDeletionDeletesNoFiles() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(schedules).delete(schedule);

        assertThatThrownBy(() -> service.delete(hostId, id)).isInstanceOf(IllegalStateException.class);

        verify(store, never()).deleteAll(any(), anyString());
    }
}
