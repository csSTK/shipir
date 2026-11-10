package dev.bethge.shipir.host;

import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ImageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * shipr-kqbc.58 / .60: the proxy's images stay protected from non-ADMINs while it is stopped when its stored compose
 * uses {@code ${VAR}} interpolation, and when an earlier deployment version used another image.
 */
class HostSystemManagedStoredProxyVariantsTest extends HostApiTestBase {

    private static final String VAR_ID = "sha256:eeeeeeeeeeeeeeeeeeeeeeee";
    private static final String OLD_ID = "sha256:cccccccccccccccccccccccc";
    private static final String PLAIN_ID = "sha256:bbbbbbbbbbbbbbbbbbbbbbbb";

    @MockitoBean ContainerService containers;
    @MockitoBean ImageService images;
    @MockitoBean NetworkService networks;
    @MockitoBean VolumeService volumes;
    @Autowired ComposeFileRepository composeFiles;

    private UUID hostId;
    private UUID proxyId;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by, system_managed) "
                + "SELECT 'shipir-proxy', ?, 'x', id, TRUE FROM users WHERE username = 'admin'", hostId);
        proxyId = jdbc.queryForObject("SELECT id FROM deployments WHERE name = 'shipir-proxy'", UUID.class);
        when(containers.listContainers(any(), any())).thenReturn(List.of());
        when(networks.list(any(), any())).thenReturn(List.of());
        when(images.list(any(), any())).thenReturn(List.of(
                new ImageDto(VAR_ID, List.of("my-traefik:9.9"), 10L, null, false),
                new ImageDto(OLD_ID, List.of("old-traefik:1"), 10L, null, false),
                new ImageDto(PLAIN_ID, List.of("nginx:1"), 10L, null, false)));
    }

    @AfterEach
    void cleanUp() {
        composeFiles.delete(hostId, "shipir-proxy");
    }

    private void storeCompose(String yaml, String env) {
        composeFiles.write(hostId, "shipir-proxy", yaml, env);
    }

    private void operatorCanNotRemove(String image) throws Exception {
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", hostId, image)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(images, never()).removeImage(any(), any(), any());
    }

    @Test
    void imageFromAVariableInTheEnvFileIsProtectedWhileTheProxyIsStopped() throws Exception {
        storeCompose("services:\n  proxy:\n    image: ${IMG}\n", "IMG=my-traefik:9.9\n");
        operatorCanNotRemove("my-traefik:9.9");
    }

    @Test
    void imageFromAVariableDefaultIsProtected() throws Exception {
        storeCompose("services:\n  proxy:\n    image: ${IMG:-my-traefik:9.9}\n", "");
        operatorCanNotRemove("my-traefik:9.9");
    }

    @Test
    void imageFromANestedDefaultVariableIsProtected() throws Exception {
        storeCompose("services:\n  proxy:\n    image: ${IMG:-${DEFAULT_IMG}}\n", "DEFAULT_IMG=my-traefik:9.9\n");
        operatorCanNotRemove("my-traefik:9.9");
    }

    @Test
    void bareDollarVariableAndPartialInterpolationAreResolved() throws Exception {
        storeCompose("services:\n  proxy:\n    image: my-traefik:$TAG\n", "TAG=9.9\n");
        operatorCanNotRemove("my-traefik:9.9");
    }

    @Test
    void anUnrelatedImageIsStillRemovableAndAdminMayRemoveTheProtectedOne() throws Exception {
        storeCompose("services:\n  proxy:\n    image: ${IMG}\n", "IMG=my-traefik:9.9\n");
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", hostId, PLAIN_ID)))
                .andExpect(status().isNoContent());
        mvc.perform(login("admin").write(delete("/api/hosts/{h}/images/{i}", hostId, VAR_ID)))
                .andExpect(status().isNoContent());
    }

    @Test
    void imageOfAnEarlierDeploymentVersionStaysProtectedAfterTheProxyMovedOn() throws Exception {
        storeCompose("services:\n  proxy:\n    image: my-traefik:9.9\n", "");
        jdbc.update("INSERT INTO deployment_versions (deployment_id, yaml_content, created_by, created_at) "
                + "SELECT ?, 'services:\n  proxy:\n    image: old-traefik:1\n', id, now() - interval '1 day' "
                + "FROM users WHERE username = 'admin'", proxyId);
        operatorCanNotRemove("old-traefik:1");
    }

    @Test
    void earlierVersionWithAVariableImageIsResolvedToo() throws Exception {
        storeCompose("services:\n  proxy:\n    image: my-traefik:9.9\n", "OLD=old-traefik:1\n");
        jdbc.update("INSERT INTO deployment_versions (deployment_id, yaml_content, created_by, created_at) "
                + "SELECT ?, 'services:\n  proxy:\n    image: ${OLD}\n', id, now() - interval '1 day' "
                + "FROM users WHERE username = 'admin'", proxyId);
        operatorCanNotRemove("old-traefik:1");
    }

    @Test
    void versionsOfOrdinaryDeploymentsDoNotProtectTheirImages() throws Exception {
        storeCompose("services:\n  proxy:\n    image: my-traefik:9.9\n", "");
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'web', ?, 'x', id FROM users WHERE username = 'admin'", hostId);
        UUID web = jdbc.queryForObject("SELECT id FROM deployments WHERE name = 'web'", UUID.class);
        jdbc.update("INSERT INTO deployment_versions (deployment_id, yaml_content, created_by) "
                + "SELECT ?, 'services:\n  w:\n    image: nginx:1\n', id FROM users WHERE username = 'admin'", web);
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", hostId, PLAIN_ID)))
                .andExpect(status().isNoContent());
    }
}
