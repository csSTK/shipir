package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeArchiveService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.VolumeDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Volumes of the system-managed proxy (decrypted certificate keys, DNS config) are ADMIN-only for backup, restore,
 * stored backups and schedules, whether the volume carries the Compose project label or is only mounted by a
 * container of that project. Ordinary volumes stay open to OPERATOR.
 */
class HostSystemManagedVolumeBackupTest extends HostApiTestBase {

    @MockitoBean VolumeArchiveService archives;
    @MockitoBean ContainerService containers;
    @MockitoBean ImageService images;
    @MockitoBean NetworkService networks;
    @MockitoBean VolumeService volumes;

    private String host;
    private UUID hostId;

    @BeforeEach
    void setUp() {
        SetupGateFixture.resetWithAdminHost(jdbc, "local");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        host = hostId.toString();
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by, system_managed) "
                + "SELECT 'shipir-proxy', ?, 'x', id, TRUE FROM users WHERE username = 'admin'", hostId);
        when(volumes.list(any(), any())).thenReturn(List.of(
                new VolumeDto("proxy_certs", "local", "/v/certs", null, true, "shipir-proxy"),
                new VolumeDto("unlabelled_conf", "local", "/v/conf", null, true, null),
                new VolumeDto("shop_data", "local", "/v/data", null, true, "shop"),
                new VolumeDto("plain", "local", "/v/plain", null, false, null)));
        when(containers.listContainers(any(), any())).thenReturn(List.of(
                new ContainerDto("px1", "proxy", "traefik:3", "running", "Up", null, null, List.of(), "shipir-proxy", "traefik"),
                new ContainerDto("sh1", "shop", "nginx", "running", "Up", null, null, List.of(), "shop", "web")));
        when(containers.inspect(any(), any(), eq("px1"))).thenReturn(details("px1", "shipir-proxy", "unlabelled_conf"));
        when(containers.inspect(any(), any(), eq("sh1"))).thenReturn(details("sh1", "shop", "shop_data"));
        when(archives.backup(any(), any(), any(), any(), anyLong())).thenAnswer(i -> new ByteArrayInputStream(new byte[]{1}));
    }

    private static ContainerDetailsDto details(String id, String project, String volume) {
        return new ContainerDetailsDto(id, "c", "img", "running", null, null, 0, null, List.of(), List.of(), false, false,
                List.of(), List.of(new ContainerDetailsDto.Mount("volume", volume, null, "/data", false)), Map.of(),
                List.of(), List.of(), null, project, "svc");
    }

    private void expectForbiddenForOperator(String volume) throws Exception {
        Login op = login("operator");
        String base = "/api/hosts/{h}/volumes/{v}";
        mvc.perform(get(base + "/backup", host, volume).session(op.session()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(base + "/stored-backups", host, volume).session(op.session())).andExpect(status().isForbidden());
        mvc.perform(get(base + "/stored-backups/x.tar", host, volume).session(op.session())).andExpect(status().isForbidden());
        mvc.perform(op.write(post(base + "/stored-backups/x.tar/restore", host, volume))).andExpect(status().isForbidden());
        mvc.perform(op.write(multipart(base + "/restore", host, volume)
                        .file(new MockMultipartFile("file", "d.tar", "application/x-tar", new byte[]{1}))))
                .andExpect(status().isForbidden());
        mvc.perform(op.write(post("/api/hosts/{h}/volume-backup-schedules", host).contentType(APPLICATION_JSON)
                        .content("{\"volumeName\":\"" + volume + "\",\"intervalHours\":24,\"keepCount\":3}")))
                .andExpect(status().isForbidden());
        verify(archives, never()).backup(any(), any(), eq(volume), any(), anyLong());
        verify(archives, never()).restore(any(), any(), eq(volume), any(), any(), anyLong(), anyBoolean());
    }

    @Test
    void operatorIsRefusedOnAVolumeLabelledWithTheProxyProject() throws Exception {
        expectForbiddenForOperator("proxy_certs");
    }

    @Test
    void operatorIsRefusedOnAnUnlabelledVolumeMountedByAProxyContainer() throws Exception {
        expectForbiddenForOperator("unlabelled_conf");
    }

    @Test
    void adminMayBackUpAndScheduleTheProxyVolume() throws Exception {
        Login admin = login("admin");
        mvc.perform(get("/api/hosts/{h}/volumes/proxy_certs/backup", host).session(admin.session())).andExpect(status().isOk());
        mvc.perform(admin.write(post("/api/hosts/{h}/volume-backup-schedules", host).contentType(APPLICATION_JSON)
                        .content("{\"volumeName\":\"unlabelled_conf\",\"intervalHours\":24,\"keepCount\":3}")))
                .andExpect(status().isCreated());
    }

    @Test
    void operatorStillBacksUpAndSchedulesOrdinaryVolumes() throws Exception {
        Login op = login("operator");
        mvc.perform(get("/api/hosts/{h}/volumes/shop_data/backup", host).session(op.session())).andExpect(status().isOk());
        mvc.perform(get("/api/hosts/{h}/volumes/plain/backup", host).session(op.session())).andExpect(status().isOk());
        mvc.perform(op.write(post("/api/hosts/{h}/volume-backup-schedules", host).contentType(APPLICATION_JSON)
                        .content("{\"volumeName\":\"shop_data\",\"intervalHours\":24,\"keepCount\":3}")))
                .andExpect(status().isCreated());
    }

    @Test
    void existingProxyScheduleIsHiddenFromTheOperatorListAndCannotBeDeletedByThem() throws Exception {
        UUID sid = jdbc.queryForObject("INSERT INTO volume_backup_schedules (host_id, volume_name, interval_hours, keep_count, "
                + "created_by, next_run_at) SELECT ?, 'proxy_certs', 24, 3, id, now() FROM users WHERE username = 'admin' RETURNING id",
                UUID.class, hostId);
        Login op = login("operator");
        mvc.perform(get("/api/hosts/{h}/volume-backup-schedules", host).session(op.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(op.write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/hosts/{h}/volume-backup-schedules/{s}", host, sid)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/hosts/{h}/volume-backup-schedules", host).session(login("admin").session()))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
