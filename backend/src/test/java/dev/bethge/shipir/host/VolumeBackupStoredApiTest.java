package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** List and download of stored scheduled archives: roles, host grants, name validation, audit. */
@TestPropertySource(properties = "shipir.volume-backup.scheduler-enabled=false")
class VolumeBackupStoredApiTest extends HostApiTestBase {

    private static final Path DIR = tempDir();
    private static final String NAME = "data-20260102T030405Z.tar";

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("shipir-stored-backups");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void backupDir(DynamicPropertyRegistry registry) {
        registry.add("shipir.volume-backup.dir", DIR::toString);
    }

    private UUID hostId;
    private String host;
    private UUID otherHostId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM audit_log");
        SetupGateFixture.resetWithAdminHost(jdbc, "local");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, host, port, owner_user_id) "
                + "SELECT 'second', 'TCP', 'second.local', 2375, id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        otherHostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'second'", UUID.class);
        host = hostId.toString();
        put(hostId, "data", NAME, "tar-bytes");
        put(hostId, "data", "data-20260101T000000Z.tar", "older");
        put(otherHostId, "data", "data-20260103T000000Z.tar", "other-host-secret");
    }

    private void put(UUID h, String volume, String name, String content) throws Exception {
        Path dir = Files.createDirectories(DIR.resolve(h.toString()).resolve(volume));
        Files.writeString(dir.resolve(name), content);
    }

    private List<String> audit() {
        return jdbc.queryForList("SELECT outcome || ':' || coalesce(target_id, '') || ':' || coalesce(details::text, '') "
                + "FROM audit_log WHERE action = 'VOLUME_BACKUP_DOWNLOAD'", String.class);
    }

    @Test
    void listReturnsNewestFirstWithSizeAndTime() throws Exception {
        mvc.perform(get("/api/hosts/{h}/volumes/data/stored-backups", host).session(login("operator").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value(NAME))
                .andExpect(jsonPath("$[0].sizeBytes").value(9))
                .andExpect(jsonPath("$[0].createdAt").value("2026-01-02T03:04:05Z"));
    }

    @Test
    void listDoesNotShowAnotherHostsArchives() throws Exception {
        mvc.perform(get("/api/hosts/{h}/volumes/data/stored-backups", otherHostId).session(login("admin").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("data-20260103T000000Z.tar"));
        mvc.perform(get("/api/hosts/{h}/volumes/nothing/stored-backups", host).session(login("admin").session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void viewerIsForbiddenOnBothEndpoints() throws Exception {
        Login l = login("viewer");
        mvc.perform(get("/api/hosts/{h}/volumes/data/stored-backups", host).session(l.session())).andExpect(status().isForbidden());
        mvc.perform(get("/api/hosts/{h}/volumes/data/stored-backups/{n}", host, NAME).session(l.session()))
                .andExpect(status().isForbidden());
        assertThat(audit()).isEmpty();
    }

    @Test
    void operatorWithOnlyViewGrantIsForbidden() throws Exception {
        grantHost("operator", hostId, "VIEW");
        Login l = login("operator");
        mvc.perform(get("/api/hosts/{h}/volumes/data/stored-backups", host).session(l.session()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/hosts/{h}/volumes/data/stored-backups/{n}", host, NAME).session(l.session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorWithoutAnyGrantSeesAnUnknownHost() throws Exception {
        Login l = login("operator");
        jdbc.update("DELETE FROM permissions");
        mvc.perform(get("/api/hosts/{h}/volumes/data/stored-backups", host).session(l.session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
        mvc.perform(get("/api/hosts/{h}/volumes/data/stored-backups/{n}", host, NAME).session(l.session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadStreamsTheArchiveWithHeadersAndIsAudited() throws Exception {
        mvc.perform(get("/api/hosts/{h}/volumes/data/stored-backups/{n}", host, NAME).session(login("operator").session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/x-tar"))
                .andExpect(header().string("Content-Disposition", containsString(NAME)))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes("tar-bytes".getBytes()));
        assertThat(audit()).hasSize(1).allSatisfy(r -> {
            assertThat(r).startsWith("SUCCESS:data:");
            assertThat(r).contains(NAME);
        });
    }

    @Test
    void downloadOfAMissingArchiveIs404AndAuditedAsFailure() throws Exception {
        mvc.perform(get("/api/hosts/{h}/volumes/data/stored-backups/{n}", host, "data-20250101T000000Z.tar")
                        .session(login("admin").session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORED_BACKUP_NOT_FOUND"));
        assertThat(audit()).hasSize(1).allSatisfy(r -> assertThat(r).startsWith("FAILURE"));
    }

    @Test
    void anotherHostsArchiveCannotBeDownloadedThroughThisHost() throws Exception {
        mvc.perform(get("/api/hosts/{h}/volumes/data/stored-backups/{n}", host, "data-20260103T000000Z.tar")
                        .session(login("admin").session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherVolumesArchiveCannotBeDownloadedThroughThisVolume() throws Exception {
        mvc.perform(get("/api/hosts/{h}/volumes/other/stored-backups/{n}", host, NAME).session(login("admin").session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESOURCE_ID"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "%2e%2e", "..%2Fdata-20260101T000000Z.tar", "%2Fetc%2Fpasswd", "data-20260101T000000Z.tar.part",
            "x.tar", "data-20260101T000000Z.tar%00", "..%5Cx.tar"})
    void maliciousNamesNeverServeAFile(String name) throws Exception {
        mvc.perform(get("/api/hosts/{h}/volumes/data/stored-backups/" + name, host).session(login("admin").session()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(400, 404))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("tar-bytes", "other-host-secret"));
    }

    @Test
    void absolutePathAsNameIsRejected() throws Exception {
        mvc.perform(get("/api/hosts/{h}/volumes/data/stored-backups/{n}", host, "/etc/passwd").session(login("admin").session()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(400, 404));
    }

    @Test
    void symlinkedArchiveIsNotServed() throws Exception {
        Path link = DIR.resolve(hostId.toString()).resolve("data").resolve("data-20260109T000000Z.tar");
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, DIR.resolve(otherHostId.toString()).resolve("data").resolve("data-20260103T000000Z.tar"));
        mvc.perform(get("/api/hosts/{h}/volumes/data/stored-backups/{n}", host, link.getFileName().toString())
                        .session(login("admin").session()))
                .andExpect(status().isNotFound());
    }
}
