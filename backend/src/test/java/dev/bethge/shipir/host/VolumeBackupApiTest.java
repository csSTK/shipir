package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.VolumeArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Volume backup/restore endpoints against a stubbed {@link VolumeArchiveService}: roles, validation, limits, audit. */
@TestPropertySource(properties = {"shipir.volume-backup.max-bytes=1KB", "shipir.volume-backup.helper-image=test/helper:1"})
class VolumeBackupApiTest extends HostApiTestBase {

    @MockitoBean
    VolumeArchiveService archives;

    private UUID hostId;
    private String host;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        host = hostId.toString();
    }

    private MockMultipartHttpServletRequestBuilder restore(Login l, String volume, byte[] data, String confirm, boolean csrf) {
        var b = multipart("/api/hosts/{h}/volumes/{v}/restore", host, volume)
                .file(new MockMultipartFile("file", "data.tar", "application/x-tar", data))
                .session(l.session());
        if (confirm != null) b.param("confirm", confirm);
        return csrf ? (MockMultipartHttpServletRequestBuilder) b.cookie(l.csrf()).header("X-XSRF-TOKEN", l.csrf().getValue()) : b;
    }

    private List<String> audit(String action) {
        return jdbc.queryForList("SELECT outcome || ':' || coalesce(target_id, '') || ':' || coalesce(details::text, '') "
                + "FROM audit_log WHERE action = ?", String.class, action);
    }

    @Test
    void viewerIsForbiddenOnBothEndpoints() throws Exception {
        Login l = login("viewer");
        mvc.perform(get("/api/hosts/{h}/volumes/data/backup", host).session(l.session())).andExpect(status().isForbidden());
        mvc.perform(restore(l, "data", new byte[]{1}, null, true)).andExpect(status().isForbidden());
        verifyNoInteractions(archives);
    }

    @Test
    void operatorWithOnlyViewGrantOnTheHostIsForbidden() throws Exception {
        grantHost("operator", hostId, "VIEW");
        Login l = login("operator");
        mvc.perform(get("/api/hosts/{h}/volumes/data/backup", host).session(l.session()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(restore(l, "data", new byte[]{1}, null, true)).andExpect(status().isForbidden());
        verifyNoInteractions(archives);
    }

    @Test
    void operatorWithoutAnyGrantSeesAnUnknownHost() throws Exception {
        Login l = login("operator");
        jdbc.update("DELETE FROM permissions");
        mvc.perform(get("/api/hosts/{h}/volumes/data/backup", host).session(l.session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
        verifyNoInteractions(archives);
    }

    @Test
    void backupStreamsTheTarWithDownloadHeadersAndIsAudited() throws Exception {
        byte[] tar = "fake-tar-bytes".getBytes();
        when(archives.backup(any(), any(), eq("data"), eq("test/helper:1"), eq(1024L)))
                .thenReturn(new ByteArrayInputStream(tar));

        mvc.perform(get("/api/hosts/{h}/volumes/data/backup", host).session(login("operator").session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/x-tar"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("data-")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".tar")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(tar));
        assertThat(audit("VOLUME_BACKUP")).hasSize(1).allSatisfy(r -> assertThat(r).startsWith("SUCCESS:data:"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "a:b", "-lead", ".hidden", "x$y", "a,b"})
    void badVolumeNamesAre400AndNeverReachDocker(String name) throws Exception {
        Login l = login("admin");
        mvc.perform(get("/api/hosts/{h}/volumes/{v}/backup", host, name).session(l.session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESOURCE_ID"));
        mvc.perform(restore(l, name, new byte[]{1}, "true", true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESOURCE_ID"));
        verifyNoInteractions(archives);
    }

    @Test
    void backupOfMissingVolumeIs404() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "x", null))
                .when(archives).backup(any(), any(), eq("gone"), any(), anyLong());
        mvc.perform(get("/api/hosts/{h}/volumes/gone/backup", host).session(login("admin").session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(audit("VOLUME_BACKUP")).allSatisfy(r -> assertThat(r).startsWith("FAILURE"));
    }

    @Test
    void restorePassesTheUploadAndConfirmFlagAndIsAudited() throws Exception {
        byte[][] received = new byte[1][];
        doAnswer(inv -> {
            received[0] = ((VolumeArchiveService.ArchiveSource) inv.getArgument(4)).open().readAllBytes();
            return null;
        }).when(archives).restore(any(), any(), eq("data"), eq("test/helper:1"), any(), eq(1024L), eq(true));

        mvc.perform(restore(login("operator"), "data", "tar-bytes".getBytes(), "true", true))
                .andExpect(status().isNoContent());

        assertThat(received[0]).isEqualTo("tar-bytes".getBytes());
        assertThat(audit("VOLUME_RESTORE")).hasSize(1).allSatisfy(r ->
                assertThat(r).startsWith("SUCCESS:data:").contains("true").doesNotContain("tar-bytes"));
    }

    @Test
    void restoreWithoutConfirmDefaultsToFalse() throws Exception {
        mvc.perform(restore(login("admin"), "data", new byte[]{1}, null, true)).andExpect(status().isNoContent());
        verify(archives).restore(any(), any(), eq("data"), any(), any(), anyLong(), eq(false));
    }

    @Test
    void restoreIntoInUseVolumeIs409AndAuditedAsFailure() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.IN_USE, "x", null))
                .when(archives).restore(any(), any(), eq("data"), any(), any(), anyLong(), eq(false));
        mvc.perform(restore(login("admin"), "data", new byte[]{1}, "false", true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VOLUME_IN_USE"));
        assertThat(audit("VOLUME_RESTORE")).allSatisfy(r -> assertThat(r).startsWith("FAILURE"));
    }

    @Test
    void restoreOfUnsafeArchiveIs400() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.INVALID_ARCHIVE, "x", null))
                .when(archives).restore(any(), any(), any(), any(), any(), anyLong(), anyBoolean());
        mvc.perform(restore(login("admin"), "data", new byte[]{1}, "true", true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARCHIVE"));
    }

    @Test
    void restoreOfMissingVolumeIs404() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "x", null))
                .when(archives).restore(any(), any(), eq("gone"), any(), any(), anyLong(), anyBoolean());
        mvc.perform(restore(login("admin"), "gone", new byte[]{1}, "true", true))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void oversizeUploadIs413AndNeverReachesDocker() throws Exception {
        mvc.perform(restore(login("admin"), "data", new byte[2048], "true", true))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("SIZE_LIMIT_EXCEEDED"));
        verify(archives, never()).restore(any(), any(), any(), any(), any(), anyLong(), anyBoolean());
    }

    @Test
    void restoreWithoutFilePartIs400() throws Exception {
        Login l = login("admin");
        mvc.perform(multipart("/api/hosts/{h}/volumes/data/restore", host).session(l.session())
                        .cookie(l.csrf()).header("X-XSRF-TOKEN", l.csrf().getValue()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(archives);
    }

    @Test
    void restoreRequiresCsrf() throws Exception {
        mvc.perform(restore(login("admin"), "data", new byte[]{1}, "true", false)).andExpect(status().isForbidden());
        verifyNoInteractions(archives);
    }

    @Test
    void unreachableHostIs502() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "x", null))
                .when(archives).backup(any(), any(), any(), any(), anyLong());
        mvc.perform(get("/api/hosts/{h}/volumes/data/backup", host).session(login("admin").session()))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("UNREACHABLE"));
    }
}
