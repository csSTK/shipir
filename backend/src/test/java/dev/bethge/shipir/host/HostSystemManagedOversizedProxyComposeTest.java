package dev.bethge.shipir.host;

import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * shipr-0qbh.99: a stored proxy compose over the size cap (413 FILE_TOO_LARGE in the compose layer) must not surface
 * as a generic 500 from guarded host operations. The guard fails closed for non-ADMIN with 409
 * PROXY_COMPOSE_UNREADABLE, logs a WARN without file content, and never looks at ADMIN callers.
 */
@ExtendWith(OutputCaptureExtension.class)
class HostSystemManagedOversizedProxyComposeTest extends HostApiTestBase {

    @MockitoBean ContainerService containers;
    @MockitoBean ImageService images;
    @MockitoBean NetworkService networks;
    @MockitoBean VolumeService volumes;
    @Autowired ComposeFileRepository composeFiles;

    private UUID hostId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by, system_managed) "
                + "SELECT 'shipir-proxy', ?, 'x', id, TRUE FROM users WHERE username = 'admin'", hostId);
        composeFiles.write(hostId, "shipir-proxy", "services:\n  proxy:\n    image: traefik:3\n", "");
        // grow the stored file past the cap behind the repository's back (write itself refuses oversized content)
        Path file = composeFiles.deploymentDir(hostId, "shipir-proxy").resolve(ComposeFileRepository.COMPOSE_FILE);
        Files.writeString(file, "# SECRETPAD " + "x".repeat((int) ComposeFileRepository.MAX_FILE_BYTES + 10));
        when(containers.listContainers(any(), any())).thenReturn(List.of());
        when(networks.list(any(), any())).thenReturn(List.of());
        when(images.list(any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void cleanUp() {
        composeFiles.delete(hostId, "shipir-proxy");
    }

    @Test
    void operatorImageDeleteFailsClosedWith409AndLogsNoContent(CapturedOutput out) throws Exception {
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", hostId, "sha256:bbbbbbbbbbbb")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROXY_COMPOSE_UNREADABLE"));
        verify(images, never()).removeImage(any(), any(), any());
        assertTrue(out.getAll().contains("cannot be read"));
        assertFalse(out.getAll().contains("SECRETPAD"));
    }

    @Test
    void operatorNetworkDeleteFailsClosedWith409() throws Exception {
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/networks/{n}", hostId, "somenet")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROXY_COMPOSE_UNREADABLE"));
    }

    @Test
    void adminIsUnaffected() throws Exception {
        mvc.perform(login("admin").write(delete("/api/hosts/{h}/images/{i}", hostId, "sha256:bbbbbbbbbbbb")))
                .andExpect(status().isNoContent());
    }
}
