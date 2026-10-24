package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Prune preview and execution against stubbed Docker services: preview equals removals, managed volumes, roles, audit. */
class HostPruneTest extends HostApiTestBase {

    @MockitoBean ContainerService containers;
    @MockitoBean ImageService images;
    @MockitoBean NetworkService networks;
    @MockitoBean VolumeService volumes;
    @MockitoBean ManagedDeploymentLookup managed;

    private static final PruneCandidateDto C1 = new PruneCandidateDto("c1", "web-1", 100L, "app");
    private static final PruneCandidateDto I1 = new PruneCandidateDto("sha256:i1", null, 1000L, null);
    private static final PruneCandidateDto I2 = new PruneCandidateDto("sha256:i2", "nginx:1", 5000L, null);
    private static final PruneCandidateDto V_PLAIN = new PruneCandidateDto("plain", "plain", null, null);
    private static final PruneCandidateDto V_MANAGED = new PruneCandidateDto("app_data", "app_data", null, "app");
    private static final PruneCandidateDto N1 = new PruneCandidateDto("n1", "mynet", null, null);

    private String host;
    private boolean noAutoGrant;

    @Override
    protected boolean autoGrantHosts() {
        return !noAutoGrant;
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        host = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
        when(containers.listPrunable(any(), any())).thenReturn(List.of(C1));
        when(images.listPrunable(any(), any(), eq(false))).thenReturn(List.of(I1));
        when(images.listPrunable(any(), any(), eq(true))).thenReturn(List.of(I1, I2));
        when(volumes.listPrunable(any(), any())).thenReturn(List.of(V_PLAIN, V_MANAGED));
        when(networks.listPrunable(any(), any())).thenReturn(List.of(N1));
        when(managed.managedDeploymentIds(any())).thenReturn(Map.of("app", UUID.randomUUID()));
    }

    private static final String ALL = "{\"containers\":true,\"images\":\"UNUSED\",\"volumes\":true,\"networks\":true}";

    @Test
    void previewListsCategoriesAndExcludesManagedVolumes() throws Exception {
        mvc.perform(get("/api/hosts/{h}/prune/preview", host).session(login("operator").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.containers.count").value(1))
                .andExpect(jsonPath("$.containers.sizeBytes").value(100))
                .andExpect(jsonPath("$.images.count").value(1))
                .andExpect(jsonPath("$.volumes.count").value(1))
                .andExpect(jsonPath("$.volumes.items[0].id").value("plain"))
                .andExpect(jsonPath("$.managedVolumesExcluded").value(1))
                .andExpect(jsonPath("$.networks.items[0].name").value("mynet"));
    }

    @Test
    void previewWithUnusedImagesAndManagedVolumesIncluded() throws Exception {
        mvc.perform(get("/api/hosts/{h}/prune/preview?images=UNUSED&includeManagedVolumes=true", host)
                        .session(login("admin").session()))
                .andExpect(jsonPath("$.images.count").value(2))
                .andExpect(jsonPath("$.images.sizeBytes").value(6000))
                .andExpect(jsonPath("$.volumes.count").value(2))
                .andExpect(jsonPath("$.managedVolumesExcluded").value(0));
    }

    @Test
    void pruneRemovesExactlyWhatPreviewListsAndAudits() throws Exception {
        mvc.perform(login("operator").write(post("/api/hosts/{h}/prune", host))
                        .contentType(MediaType.APPLICATION_JSON).content(ALL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.containers.removed").value(1))
                .andExpect(jsonPath("$.images.removed").value(2))
                .andExpect(jsonPath("$.volumes.removed").value(1))
                .andExpect(jsonPath("$.networks.removed").value(1))
                .andExpect(jsonPath("$.reclaimedBytes").value(6100));
        verify(containers).removeContainer(any(), any(), eq("c1"), eq(false));
        verify(images).removeImage(any(), any(), eq("sha256:i1"));
        verify(images).removeImage(any(), any(), eq("sha256:i2"));
        verify(volumes).removeVolume(any(), any(), eq("plain"));
        verify(volumes, never()).removeVolume(any(), any(), eq("app_data"));
        verify(networks).removeNetwork(any(), any(), eq("n1"));

        Map<String, Object> details = jdbc.queryForMap(
                "SELECT outcome, target_id, details::text AS details FROM audit_log WHERE action = 'RESOURCE_PRUNE'");
        assertThat(details.get("outcome")).isEqualTo("SUCCESS");
        assertThat(details.get("target_id")).isEqualTo(host);
        assertThat((String) details.get("details")).contains("\"containers\": 1").contains("\"images\": 2")
                .contains("\"reclaimedBytes\": 6100");
    }

    @Test
    void managedVolumesAreRemovedOnlyWhenExplicit() throws Exception {
        mvc.perform(login("admin").write(post("/api/hosts/{h}/prune", host))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"volumes\":true,\"includeManagedVolumes\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.volumes.removed").value(2));
        verify(volumes).removeVolume(any(), any(), eq("app_data"));
        verify(containers, never()).removeContainer(any(), any(), any(), anyBoolean());
    }

    @Test
    void candidatesThatChangedMeanwhileAreSkipped() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.IN_USE, "x", null))
                .when(volumes).removeVolume(any(), any(), eq("plain"));
        mvc.perform(login("admin").write(post("/api/hosts/{h}/prune", host))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"volumes\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.volumes.removed").value(0))
                .andExpect(jsonPath("$.volumes.skipped").value(1));
    }

    @Test
    void emptySelectionIs400() throws Exception {
        mvc.perform(login("admin").write(post("/api/hosts/{h}/prune", host))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTHING_SELECTED"));
    }

    @Test
    void daemonFailureIs502AndAuditFailure() throws Exception {
        when(containers.listPrunable(any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "x", null));
        mvc.perform(login("admin").write(post("/api/hosts/{h}/prune", host))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"containers\":true}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("UNREACHABLE"));
        assertThat(jdbc.queryForObject("SELECT outcome FROM audit_log WHERE action = 'RESOURCE_PRUNE'", String.class))
                .isEqualTo("FAILURE");
    }

    @Test
    void viewerIs403ForPreviewAndPrune() throws Exception {
        Login viewer = login("viewer");
        mvc.perform(get("/api/hosts/{h}/prune/preview", host).session(viewer.session())).andExpect(status().isForbidden());
        mvc.perform(viewer.write(post("/api/hosts/{h}/prune", host)).contentType(MediaType.APPLICATION_JSON).content(ALL))
                .andExpect(status().isForbidden());
        verify(containers, never()).removeContainer(any(), any(), any(), anyBoolean());
    }

    @Test
    void operatorWithoutHostOperateGrantIs403AndUnknownHostIs404() throws Exception {
        noAutoGrant = true;
        grantHost("operator", UUID.fromString(host), "VIEW");
        Login op = login("operator");
        mvc.perform(op.write(post("/api/hosts/{h}/prune", host)).contentType(MediaType.APPLICATION_JSON).content(ALL))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/hosts/{h}/prune/preview", host).session(op.session())).andExpect(status().isForbidden());
        mvc.perform(op.write(post("/api/hosts/{h}/prune", UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
                .content(ALL)).andExpect(status().isNotFound());
        verify(volumes, never()).removeVolume(any(), any(), any());
    }

    @Test
    void csrfIsRequiredAndAnonymousIs401() throws Exception {
        mvc.perform(post("/api/hosts/{h}/prune", host).session(login("admin").session())
                        .contentType(MediaType.APPLICATION_JSON).content(ALL))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/hosts/{h}/prune/preview", host)).andExpect(status().isUnauthorized());
    }
}
