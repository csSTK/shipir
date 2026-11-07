package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.VolumeArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Restore from a stored scheduled archive: same pipeline as an upload, plus roles, name safety, audit. */
@TestPropertySource(properties = {"shipir.volume-backup.scheduler-enabled=false", "shipir.volume-backup.max-bytes=1KB",
        "shipir.volume-backup.helper-image=test/helper:1"})
class VolumeBackupStoredRestoreApiTest extends HostApiTestBase {

    private static final Path DIR = tempDir();
    private static final String NAME = "data-20260102T030405Z.tar";

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("shipir-stored-restore");
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

    private UUID hostId;
    private String host;
    private UUID otherHostId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, host, port, owner_user_id) "
                + "SELECT 'second', 'TCP', 'second.local', 2375, id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        otherHostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'second'", UUID.class);
        host = hostId.toString();
        put(hostId, "data", NAME, "tar-bytes");
        put(otherHostId, "data", "data-20260103T000000Z.tar", "other-host-secret");
    }

    private Path put(UUID h, String volume, String name, String content) throws Exception {
        Path dir = Files.createDirectories(DIR.resolve(h.toString()).resolve(volume));
        return Files.writeString(dir.resolve(name), content);
    }

    private MockHttpServletRequestBuilder restore(Login l, String volume, String name, String confirm, boolean csrf) {
        var b = post("/api/hosts/{h}/volumes/{v}/stored-backups/" + name + "/restore", host, volume).session(l.session());
        if (confirm != null) b.param("confirm", confirm);
        return csrf ? b.cookie(l.csrf()).header("X-XSRF-TOKEN", l.csrf().getValue()) : b;
    }

    private List<String> audit() {
        return jdbc.queryForList("SELECT outcome || ':' || coalesce(target_id, '') || ':' || coalesce(details::text, '') "
                + "FROM audit_log WHERE action = 'VOLUME_RESTORE'", String.class);
    }

    @Test
    void restoreFeedsTheStoredBytesAndConfirmFlagToTheUploadPipelineAndIsAudited() throws Exception {
        byte[][] received = new byte[1][];
        doAnswer(inv -> {
            received[0] = ((VolumeArchiveService.ArchiveSource) inv.getArgument(4)).open().readAllBytes();
            return null;
        }).when(archives).restore(any(), any(), eq("data"), eq("test/helper:1"), any(), eq(1024L), eq(true));

        mvc.perform(restore(login("operator"), "data", NAME, "true", true)).andExpect(status().isNoContent());

        assertThat(received[0]).isEqualTo("tar-bytes".getBytes());
        assertThat(audit()).hasSize(1).allSatisfy(r -> {
            assertThat(r).startsWith("SUCCESS:data:");
            assertThat(r).contains(NAME).contains("stored").contains("true").doesNotContain("tar-bytes");
        });
    }

    @Test
    void restoreWithoutConfirmDefaultsToFalse() throws Exception {
        mvc.perform(restore(login("admin"), "data", NAME, null, true)).andExpect(status().isNoContent());
        verify(archives).restore(any(), any(), eq("data"), any(), any(), anyLong(), eq(false));
    }

    @Test
    void restoreIntoInUseVolumeWithoutConfirmIs409AndAuditedAsFailure() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.IN_USE, "x", null))
                .when(archives).restore(any(), any(), eq("data"), any(), any(), anyLong(), eq(false));
        mvc.perform(restore(login("admin"), "data", NAME, "false", true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VOLUME_IN_USE"));
        assertThat(audit()).hasSize(1).allSatisfy(r -> assertThat(r).startsWith("FAILURE"));
    }

    @Test
    void unsafeArchiveIsRejectedAs400() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.INVALID_ARCHIVE, "x", null))
                .when(archives).restore(any(), any(), any(), any(), any(), anyLong(), anyBoolean());
        mvc.perform(restore(login("admin"), "data", NAME, "true", true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARCHIVE"));
    }

    @Test
    void missingVolumeIs404() throws Exception {
        put(hostId, "gone", "gone-20260102T030405Z.tar", "x");
        doThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "x", null))
                .when(archives).restore(any(), any(), eq("gone"), any(), any(), anyLong(), anyBoolean());
        mvc.perform(restore(login("admin"), "gone", "gone-20260102T030405Z.tar", "true", true))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void oversizeStoredArchiveIs413AndNeverReachesDocker() throws Exception {
        put(hostId, "data", "data-20260105T000000Z.tar", "x".repeat(2048));
        mvc.perform(restore(login("admin"), "data", "data-20260105T000000Z.tar", "true", true))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("SIZE_LIMIT_EXCEEDED"));
        verifyNoInteractions(archives);
    }

    @Test
    void missingArchiveIs404AndNeverReachesDocker() throws Exception {
        mvc.perform(restore(login("admin"), "data", "data-20250101T000000Z.tar", "true", true))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORED_BACKUP_NOT_FOUND"));
        verifyNoInteractions(archives);
        assertThat(audit()).hasSize(1).allSatisfy(r -> assertThat(r).startsWith("FAILURE"));
    }

    @Test
    void viewerIsForbidden() throws Exception {
        mvc.perform(restore(login("viewer"), "data", NAME, "true", true)).andExpect(status().isForbidden());
        verifyNoInteractions(archives);
        assertThat(audit()).isEmpty();
    }

    @Test
    void operatorWithOnlyViewGrantIsForbiddenBeforeTheFileIsLookedUp() throws Exception {
        grantHost("operator", hostId, "VIEW");
        // a name that does not exist: 403 (not 404) proves the permission check runs before resolve
        mvc.perform(restore(login("operator"), "data", "data-20250101T000000Z.tar", "true", true))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(archives);
    }

    @Test
    void operatorWithoutAnyGrantSeesAnUnknownHost() throws Exception {
        Login l = login("operator");
        jdbc.update("DELETE FROM permissions");
        mvc.perform(restore(l, "data", NAME, "true", true))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
        verifyNoInteractions(archives);
    }

    @Test
    void restoreRequiresCsrf() throws Exception {
        mvc.perform(restore(login("admin"), "data", NAME, "true", false)).andExpect(status().isForbidden());
        verifyNoInteractions(archives);
    }

    @Test
    void anotherHostsArchiveCannotBeRestoredThroughThisHost() throws Exception {
        mvc.perform(restore(login("admin"), "data", "data-20260103T000000Z.tar", "true", true)).andExpect(status().isNotFound());
        verifyNoInteractions(archives);
    }

    @Test
    void badVolumeNameIs400() throws Exception {
        mvc.perform(restore(login("admin"), "..bad", NAME, "true", true)).andExpect(status().isBadRequest());
        verifyNoInteractions(archives);
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "%2e%2e", "..%2Fdata-20260102T030405Z.tar", "%2Fetc%2Fpasswd", "data-20260102T030405Z.tar.part",
            "x.tar", "data-20260102T030405Z.tar%00", "..%5Cx.tar"})
    void maliciousNamesNeverReachDocker(String name) throws Exception {
        mvc.perform(restore(login("admin"), "data", name, "true", true))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(400, 404));
        verifyNoInteractions(archives);
    }

    @Test
    void symlinkedArchiveIsNotRestored() throws Exception {
        Path link = DIR.resolve(hostId.toString()).resolve("data").resolve("data-20260109T000000Z.tar");
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, DIR.resolve(otherHostId.toString()).resolve("data").resolve("data-20260103T000000Z.tar"));
        mvc.perform(restore(login("admin"), "data", link.getFileName().toString(), "true", true)).andExpect(status().isNotFound());
        verifyNoInteractions(archives);
    }

    @Test
    void symlinkSwappedInDuringTheRestoreIsNeverRead() throws Exception {
        Path file = DIR.resolve(hostId.toString()).resolve("data").resolve("data-20260110T000000Z.tar");
        Files.writeString(file, "mine");
        Path secret = DIR.resolve(otherHostId.toString()).resolve("data").resolve("data-20260103T000000Z.tar");
        List<String> read = new ArrayList<>();
        doAnswer(inv -> {
            Files.delete(file);
            Files.createSymbolicLink(file, secret);
            try (InputStream in = ((VolumeArchiveService.ArchiveSource) inv.getArgument(4)).open()) {
                read.add(new String(in.readAllBytes()));
            }
            return null;
        }).when(archives).restore(any(), any(), eq("data"), any(), any(), anyLong(), anyBoolean());

        mvc.perform(restore(login("admin"), "data", file.getFileName().toString(), "true", true))
                .andExpect(status().isNoContent());

        assertThat(read).containsExactly("mine");
        Files.delete(file);
    }

    @Test
    void archiveDeletedDuringTheRestoreDoesNotAffectIt() throws Exception {
        Path file = put(hostId, "data", "data-20260111T000000Z.tar", "mine");
        doAnswer(inv -> {
            Files.delete(file);
            try (InputStream in = ((VolumeArchiveService.ArchiveSource) inv.getArgument(4)).open()) {
                in.readAllBytes();
            }
            return null;
        }).when(archives).restore(any(), any(), eq("data"), any(), any(), anyLong(), anyBoolean());

        mvc.perform(restore(login("admin"), "data", file.getFileName().toString(), "true", true))
                .andExpect(status().isNoContent());
    }

    @Test
    void unsafeStoredArchiveMessageDoesNotTalkAboutAnUpload() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.INVALID_ARCHIVE, "x", null))
                .when(archives).restore(any(), any(), anyString(), any(), any(), anyLong(), anyBoolean());
        mvc.perform(restore(login("admin"), "data", NAME, "true", true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("upload"))));
    }

    @Test
    void neverCalledWithoutAValidName() throws Exception {
        mvc.perform(restore(login("admin"), "data", "nope", "true", true));
        verify(archives, never()).restore(any(), any(), any(), any(), any(), anyLong(), anyBoolean());
    }
}
