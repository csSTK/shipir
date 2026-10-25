package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A database error while recording one run must not abort the other due schedules of the tick. */
class VolumeBackupSchedulerResilienceTest {

    @Test
    void failingRecordRunDoesNotStopTheRemainingSchedules() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID host = UUID.randomUUID();
        VolumeBackupSchedule first = new VolumeBackupSchedule(host, "one", 1, 1, UUID.randomUUID(), now);
        VolumeBackupSchedule second = new VolumeBackupSchedule(host, "two", 1, 1, UUID.randomUUID(), now);
        VolumeBackupScheduleRepository repo = mock(VolumeBackupScheduleRepository.class);
        when(repo.findByNextRunAtLessThanEqualOrderByNextRunAt(any())).thenReturn(List.of(first, second));
        VolumeBackupScheduleService scheduleService = mock(VolumeBackupScheduleService.class);
        doThrow(new IllegalStateException("db down")).when(scheduleService).recordRun(any(), anyBoolean());
        VolumeBackupService backups = mock(VolumeBackupService.class);

        new VolumeBackupScheduler(repo, scheduleService, backups, Clock.fixed(now, ZoneOffset.UTC)).runDue();

        verify(backups).storeScheduledBackup(eq(host), eq("one"), anyInt());
        verify(backups).storeScheduledBackup(eq(host), eq("two"), anyInt());
        verify(scheduleService, times(2)).recordRun(any(), anyBoolean());
    }
}
