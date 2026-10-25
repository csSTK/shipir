package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.dto.ContainerFileEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Container file endpoints against a stubbed {@link ContainerFileService}: roles, paths, limits, errors, audit. */
@TestPropertySource(properties = {"shipir.container-files.max-bytes=1KB", "shipir.container-files.max-scan-bytes=4KB"})
class ContainerFilesApiTest extends HostApiTestBase {

    @MockitoBean
    ContainerFileService files;

    private String host;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        host = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder put(
            Login l, String fileName, byte[] data, String path, boolean csrf) {
        var b = multipart(org.springframework.http.HttpMethod.PUT, "/api/hosts/{h}/containers/c1/files", host)
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", data))
                .param("path", path).session(l.session());
        return csrf ? (org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder)
                b.cookie(l.csrf()).header("X-XSRF-TOKEN", l.csrf().getValue()) : b;
    }

    private List<String> audit(String action) {
        return jdbc.queryForList("SELECT outcome || ':' || coalesce(target_id, '') || ':' || coalesce(details::text, '') "
                + "FROM audit_log WHERE action = ?", String.class, action);
    }

    private static byte[] tarOfFile(String name, byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            TarArchiveEntry e = new TarArchiveEntry(name);
            e.setSize(data.length);
            tar.putArchiveEntry(e);
            tar.write(data);
            tar.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    private static byte[] tarOfDir() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            tar.putArchiveEntry(new TarArchiveEntry("etc/"));
            tar.closeArchiveEntry();
            TarArchiveEntry e = new TarArchiveEntry("etc/a.conf");
            e.setSize(2);
            tar.putArchiveEntry(e);
            tar.write("hi".getBytes(StandardCharsets.UTF_8));
            tar.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    @Test
    void nonAdminRolesAreForbiddenOnAllThreeEndpoints() throws Exception {
        for (String user : new String[]{"operator", "viewer"}) {
            Login l = login(user);
            mvc.perform(get("/api/hosts/{h}/containers/c1/files?path=/", host).session(l.session()))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/hosts/{h}/containers/c1/files/download?path=/a", host).session(l.session()))
                    .andExpect(status().isForbidden());
            mvc.perform(put(l, "a.txt", "x".getBytes(), "/tmp", true))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(files);
    }

    @Test
    void listReturnsEntries() throws Exception {
        when(files.list(any(), any(), eq("c1"), eq("/etc"), eq(4096L))).thenReturn(List.of(
                new ContainerFileEntry("a.conf", "/etc/a.conf", ContainerFileEntry.Type.FILE, 2, null, 420, null)));
        mvc.perform(get("/api/hosts/{h}/containers/c1/files?path=/etc", host).session(login("admin").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("a.conf"))
                .andExpect(jsonPath("$[0].type").value("FILE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative/path", "/a/../etc", "/a/b\u0000c", ""})
    void maliciousPathsAre400OnAllEndpoints(String path) throws Exception {
        Login l = login("admin");
        mvc.perform(get("/api/hosts/{h}/containers/c1/files", host).queryParam("path", path).session(l.session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PATH"));
        mvc.perform(get("/api/hosts/{h}/containers/c1/files/download", host).queryParam("path", path).session(l.session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PATH"));
        mvc.perform(put(l, "a.txt", "x".getBytes(), path, true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PATH"));
        verifyNoInteractions(files);
    }

    @Test
    void invalidContainerIdIs400() throws Exception {
        mvc.perform(get("/api/hosts/{h}/containers/-bad/files?path=/", host).session(login("admin").session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESOURCE_ID"));
    }

    @Test
    void dockerErrorsMap() throws Exception {
        Login l = login("admin");
        doThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "x", null))
                .when(files).list(any(), any(), any(), eq("/nope"), anyLong());
        mvc.perform(get("/api/hosts/{h}/containers/c1/files?path=/nope", host).session(l.session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        doThrow(new DockerConnectionException(DockerErrorCode.DIRECTORY_TOO_LARGE, "x", null))
                .when(files).list(any(), any(), any(), eq("/big"), anyLong());
        mvc.perform(get("/api/hosts/{h}/containers/c1/files?path=/big", host).session(l.session()))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("DIRECTORY_TOO_LARGE"));
        doThrow(new DockerConnectionException(DockerErrorCode.INVALID_PATH, "x", null))
                .when(files).list(any(), any(), any(), eq("/file"), anyLong());
        mvc.perform(get("/api/hosts/{h}/containers/c1/files?path=/file", host).session(l.session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PATH"));
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "x", null))
                .when(files).list(any(), any(), any(), eq("/down"), anyLong());
        mvc.perform(get("/api/hosts/{h}/containers/c1/files?path=/down", host).session(l.session()))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("UNREACHABLE"));
    }

    @Test
    void unknownHostIs404() throws Exception {
        mvc.perform(get("/api/hosts/{h}/containers/c1/files?path=/", UUID.randomUUID()).session(login("admin").session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    @Test
    void singleFileDownloadIsRawBytesWithExactLengthAndAudit() throws Exception {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        when(files.download(any(), any(), eq("c1"), eq("/etc/a.txt"), eq(1024L)))
                .thenReturn(new ByteArrayInputStream(tarOfFile("a.txt", data)));
        mvc.perform(get("/api/hosts/{h}/containers/c1/files/download?path=/etc/a.txt", host).session(login("admin").session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Length", String.valueOf(data.length)))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("a.txt")))
                .andExpect(content().bytes(data));
        assertThat(audit("CONTAINER_FILE_DOWNLOAD")).hasSize(1)
                .allSatisfy(row -> assertThat(row).startsWith("SUCCESS:c1:").contains("/etc/a.txt").doesNotContain("hello"));
    }

    @Test
    void directoryDownloadIsTheTarArchive() throws Exception {
        byte[] tar = tarOfDir();
        when(files.download(any(), any(), eq("c1"), eq("/etc"), anyLong())).thenReturn(new ByteArrayInputStream(tar));
        byte[] body = mvc.perform(get("/api/hosts/{h}/containers/c1/files/download?path=/etc", host)
                        .session(login("admin").session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("etc.tar")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(tar);
    }

    @Test
    void oversizeSingleFileIs413BeforeAnyBytesAreSent() throws Exception {
        when(files.download(any(), any(), any(), eq("/big"), anyLong()))
                .thenReturn(new ByteArrayInputStream(tarOfFile("big", new byte[2048])));
        mvc.perform(get("/api/hosts/{h}/containers/c1/files/download?path=/big", host).session(login("admin").session()))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("SIZE_LIMIT_EXCEEDED"));
        assertThat(audit("CONTAINER_FILE_DOWNLOAD")).allSatisfy(r -> assertThat(r).startsWith("FAILURE"));
    }

    @Test
    void uploadBuildsOneEntryTarWithoutLoggingContent() throws Exception {
        byte[] data = "secret-content".getBytes(StandardCharsets.UTF_8);
        byte[][] received = new byte[1][];
        org.mockito.Mockito.doAnswer(inv -> {
            received[0] = ((InputStream) inv.getArgument(4)).readAllBytes();
            return null;
        }).when(files).upload(any(), any(), eq("c1"), eq("/tmp"), any(), anyLong());

        Login l = login("admin");
        mvc.perform(put(l, "note.txt", data, "/tmp", true))
                .andExpect(status().isNoContent());

        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(received[0]))) {
            TarArchiveEntry e = tar.getNextEntry();
            assertThat(e.getName()).isEqualTo("note.txt");
            assertThat(e.getSize()).isEqualTo(data.length);
            assertThat(tar.readAllBytes()).isEqualTo(data);
            assertThat(tar.getNextEntry()).isNull();
        }
        assertThat(received[0].length % 512).isZero();
        assertThat(audit("CONTAINER_FILE_UPLOAD")).hasSize(1).allSatisfy(r ->
                assertThat(r).startsWith("SUCCESS:c1:").contains("/tmp").contains("note.txt").doesNotContain("secret-content"));
    }

    @Test
    void oversizeUploadIs413() throws Exception {
        mvc.perform(put(login("admin"), "big.bin", new byte[2048], "/tmp", true))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("SIZE_LIMIT_EXCEEDED"));
        verifyNoInteractions(files);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil", "a/b", "..", "a\\b"})
    void maliciousUploadFileNamesAre400(String name) throws Exception {
        mvc.perform(put(login("admin"), name, "x".getBytes(), "/tmp", true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILE_NAME"));
        verifyNoInteractions(files);
    }

    @Test
    void uploadToMissingDirectoryIs404AndAuditedAsFailure() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "x", null))
                .when(files).upload(any(), any(), any(), eq("/gone"), any(), anyLong());
        mvc.perform(put(login("admin"), "a.txt", "x".getBytes(), "/gone", true))
                .andExpect(status().isNotFound());
        assertThat(audit("CONTAINER_FILE_UPLOAD")).allSatisfy(r -> assertThat(r).startsWith("FAILURE"));
    }

    @Test
    void uploadRequiresCsrf() throws Exception {
        mvc.perform(put(login("admin"), "a.txt", "x".getBytes(), "/tmp", false))
                .andExpect(status().isForbidden());
        verify(files, org.mockito.Mockito.never()).upload(any(), any(), any(), any(), any(), anyLong());
    }
}
