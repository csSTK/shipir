package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.VolumeArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** The scheduled run: due selection, file storage, retention, failure isolation, system audit. */
@TestPropertySource(properties = {"shipir.volume-backup.scheduler-enabled=true", "shipir.volume-backup.tick=1h"})
class VolumeBackupSchedulerTest extends HostApiTestBase {

    private static final Path DIR = tempDir();

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("shipir-volume-backups");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void backupDir(DynamicPropertyRegistry registry) {
        registry.add("shipir.volume-backup.dir", DIR::toString);
    }

    @MockitoBean
    VolumeArchiveService archives;

    @Autowired
    VolumeBackupScheduler scheduler;

    private UUID hostId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        SetupGateFixture.resetWithAdminHost(jdbc, "local");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
    }

    private UUID schedule(String volume, int interval, int keep, Instant nextRun) {
        return jdbc.queryForObject("INSERT INTO volume_backup_schedules (host_id, volume_name, interval_hours, keep_count, "
                        + "next_run_at, created_by) SELECT ?, ?, ?, ?, ?, id FROM users WHERE username = 'admin' RETURNING id",
                UUID.class, hostId, volume, interval, keep, Timestamp.from(nextRun));
    }

    private Path volumeDir(String volume) {
        return DIR.resolve(hostId.toString()).resolve(volume);
    }

    private List<String> tarFiles(String volume) throws IOException {
        Path dir = volumeDir(volume);
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT last_status, last_run_at, next_run_at, interval_hours FROM volume_backup_schedules WHERE id = ?", id);
    }

    @Test
    void dueScheduleWritesTheArchiveAndMovesNextRunByTheInterval() throws Exception {
        UUID id = schedule("data", 6, 3, Instant.now().minusSeconds(60));
        when(archives.backup(any(), any(), eq("data"), any(), anyLong()))
                .thenReturn(new ByteArrayInputStream("tar-content".getBytes()));

        Instant before = Instant.now();
        scheduler.runDue();

        List<String> files = tarFiles("data");
        assertThat(files).hasSize(1).allSatisfy(f -> assertThat(f).startsWith("data-").endsWith(".tar"));
        assertThat(Files.readString(volumeDir("data").resolve(files.get(0)))).isEqualTo("tar-content");
        Map<String, Object> row = row(id);
        assertThat(row.get("last_status")).isEqualTo("SUCCESS");
        assertThat(((Timestamp) row.get("last_run_at")).toInstant()).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(((Timestamp) row.get("next_run_at")).toInstant())
                .isBetween(before.plusSeconds(6 * 3600 - 5), Instant.now().plusSeconds(6 * 3600 + 5));
        assertThat(jdbc.queryForList("SELECT username || ':' || outcome || ':' || target_id FROM audit_log WHERE action = 'VOLUME_BACKUP'",
                String.class)).containsExactly("system:SUCCESS:data");
    }

    @Test
    void scheduleThatIsNotDueYetIsSkipped() throws Exception {
        UUID id = schedule("data", 6, 3, Instant.now().plusSeconds(3600));
        scheduler.runDue();
        assertThat(tarFiles("data")).isEmpty();
        assertThat(row(id).get("last_status")).isNull();
        org.mockito.Mockito.verifyNoInteractions(archives);
    }

    @Test
    void retentionKeepsOnlyTheNewestArchivesAndIgnoresOtherFiles() throws Exception {
        schedule("data", 6, 3, Instant.now().minusSeconds(60));
        Files.createDirectories(volumeDir("data"));
        for (String ts : new String[]{"20250101T000000Z", "20250102T000000Z", "20250103T000000Z", "20250104T000000Z"}) {
            Files.writeString(volumeDir("data").resolve("data-" + ts + ".tar"), "old");
        }
        Files.writeString(volumeDir("data").resolve("notes.txt"), "mine");
        when(archives.backup(any(), any(), eq("data"), any(), anyLong())).thenReturn(new ByteArrayInputStream("new".getBytes()));

        scheduler.runDue();

        List<String> files = tarFiles("data");
        assertThat(files).contains("notes.txt").doesNotContain("data-20250101T000000Z.tar", "data-20250102T000000Z.tar");
        assertThat(files.stream().filter(f -> f.endsWith(".tar"))).hasSize(3).contains("data-20250104T000000Z.tar");
    }

    @Test
    void failingBackupIsMarkedFailedKeepsOldArchivesAndDoesNotStopOtherSchedules() throws Exception {
        UUID bad = schedule("bad", 6, 1, Instant.now().minusSeconds(120));
        UUID good = schedule("good", 6, 1, Instant.now().minusSeconds(60));
        Files.createDirectories(volumeDir("bad"));
        Files.writeString(volumeDir("bad").resolve("bad-20250101T000000Z.tar"), "old");
        when(archives.backup(any(), any(), eq("bad"), any(), anyLong()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "x", null));
        when(archives.backup(any(), any(), eq("good"), any(), anyLong())).thenReturn(new ByteArrayInputStream("ok".getBytes()));

        scheduler.runDue();

        assertThat(row(bad).get("last_status")).isEqualTo("FAILED");
        assertThat(((Timestamp) row(bad).get("next_run_at")).toInstant()).isAfter(Instant.now());
        assertThat(tarFiles("bad")).containsExactly("bad-20250101T000000Z.tar");
        assertThat(row(good).get("last_status")).isEqualTo("SUCCESS");
        assertThat(tarFiles("good")).hasSize(1);
        assertThat(jdbc.queryForList("SELECT outcome || ':' || target_id FROM audit_log WHERE action = 'VOLUME_BACKUP' ORDER BY outcome",
                String.class)).containsExactly("FAILURE:bad", "SUCCESS:good");
    }

    @Test
    void streamFailingMidwayLeavesNoPartialFile() throws Exception {
        UUID id = schedule("data", 6, 3, Instant.now().minusSeconds(60));
        InputStream broken = new InputStream() {
            private int n;

            @Override
            public int read() throws IOException {
                if (n++ < 100) return 'x';
                throw new IOException("connection lost");
            }
        };
        when(archives.backup(any(), any(), eq("data"), any(), anyLong())).thenReturn(broken);

        scheduler.runDue();

        assertThat(row(id).get("last_status")).isEqualTo("FAILED");
        assertThat(tarFiles("data")).isEmpty();
    }

    @Test
    void scheduleOfADeletedHostIsRemovedWithIt() {
        UUID id = schedule("data", 6, 3, Instant.now().minusSeconds(60));
        jdbc.update("DELETE FROM docker_hosts WHERE id = ?", hostId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM volume_backup_schedules WHERE id = ?", Integer.class, id)).isZero();
    }
}
