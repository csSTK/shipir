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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * shipr-kqbc.63: when a proxy compose image reference cannot be resolved (variable only in the server environment or in
 * an old version's encrypted env), the guard cannot know the image, so OPERATOR image deletes on the host fail closed
 * with 409 PROXY_IMAGE_UNRESOLVED. ADMIN is unaffected, resolvable references keep the existing 403 behaviour.
 */
@ExtendWith(OutputCaptureExtension.class)
class HostSystemManagedUnresolvedProxyImageTest extends HostApiTestBase {

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

    @Test
    void operatorDeleteIsBlockedWith409WhenTheCurrentComposeHasAnUnresolvedImageVariable(CapturedOutput out) throws Exception {
        storeCompose("services:\n  proxy:\n    image: ${SECRET_IMG_VAR}\n", "");
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", hostId, PLAIN_ID)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROXY_IMAGE_UNRESOLVED"));
        verify(images, never()).removeImage(any(), any(), any());
        assertTrue(out.getAll().contains("PROXY_IMAGE_UNRESOLVED") || out.getAll().contains("unresolved"));
    }

    @Test
    void warningNamesNoVariableValues(CapturedOutput out) throws Exception {
        storeCompose("services:\n  proxy:\n    image: reg.example/${SECRET_IMG_VAR}:1\n", "OTHER=topsecretvalue\n");
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", hostId, PLAIN_ID)))
                .andExpect(status().isConflict());
        assertTrue(out.getAll().contains("unresolved"));
        assertFalse(out.getAll().contains("reg.example"));
        assertFalse(out.getAll().contains("topsecretvalue"));
    }

    @Test
    void unresolvedVariableOnlyInAnEarlierVersionBlocksToo() throws Exception {
        storeCompose("services:\n  proxy:\n    image: my-traefik:9.9\n", "");
        jdbc.update("INSERT INTO deployment_versions (deployment_id, yaml_content, created_by, created_at) "
                + "SELECT ?, 'services:\n  proxy:\n    image: ${ONLY_IN_OLD_ENC_ENV}\n', id, now() - interval '1 day' "
                + "FROM users WHERE username = 'admin'", proxyId);
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", hostId, PLAIN_ID)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROXY_IMAGE_UNRESOLVED"));
    }

    @Test
    void adminIsUnaffected() throws Exception {
        storeCompose("services:\n  proxy:\n    image: ${SECRET_IMG_VAR}\n", "");
        mvc.perform(login("admin").write(delete("/api/hosts/{h}/images/{i}", hostId, PLAIN_ID)))
                .andExpect(status().isNoContent());
    }

    @Test
    void resolvableReferencesKeepTheExistingBehaviour() throws Exception {
        storeCompose("services:\n  proxy:\n    image: ${IMG}\n", "IMG=my-traefik:9.9\n");
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", hostId, VAR_ID)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{i}", hostId, PLAIN_ID)))
                .andExpect(status().isNoContent());
    }
}
