package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DELETE endpoints against stubbed Docker services: status mapping, roles, CSRF, audit rows. */
class HostResourceDeleteTest extends HostApiTestBase {

    @MockitoBean
    ContainerService containers;
    @MockitoBean
    ImageService images;
    @MockitoBean
    NetworkService networks;
    @MockitoBean
    VolumeService volumes;

    @BeforeEach
    void cleanAudit() {
        jdbc.update("DELETE FROM audit_log");
    }

    private String localHostId() {
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        return jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    private List<String> audit(String action) {
        return jdbc.queryForList("SELECT outcome || ':' || coalesce(target_id, '') FROM audit_log WHERE action = ?",
                String.class, action);
    }

    @ParameterizedTest
    @CsvSource({"containers,abc123,CONTAINER_DELETE", "images,sha256:abc123,IMAGE_DELETE",
            "volumes,my_data-1.v,VOLUME_DELETE", "networks,net0123,NETWORK_DELETE"})
    void operatorDeletesWith204AndAuditRow(String resource, String id, String action) throws Exception {
        String host = localHostId();
        Login op = login("operator");
        mvc.perform(op.write(delete("/api/hosts/{h}/{r}/{id}", host, resource, id))).andExpect(status().isNoContent());
        assertThat(audit(action)).containsExactly("SUCCESS:" + id);
        verifyCalled(resource, id, false);
    }

    private void verifyCalled(String resource, String id, boolean force) {
        switch (resource) {
            case "containers" -> verify(containers).removeContainer(any(), any(), eq(id), eq(force));
            case "images" -> verify(images).removeImage(any(), any(), eq(id));
            case "volumes" -> verify(volumes).removeVolume(any(), any(), eq(id));
            default -> verify(networks).removeNetwork(any(), any(), eq(id));
        }
    }

    @Test
    void forceFlagIsPassedToContainerRemoval() throws Exception {
        String host = localHostId();
        mvc.perform(login("admin").write(delete("/api/hosts/{h}/containers/c1?force=true", host)))
                .andExpect(status().isNoContent());
        verifyCalled("containers", "c1", true);
    }

    @ParameterizedTest
    @CsvSource({"NOT_FOUND,404,RESOURCE_NOT_FOUND", "IN_USE,409,RESOURCE_IN_USE",
            "BUILTIN_NETWORK,409,RESOURCE_IN_USE", "UNREACHABLE,502,UNREACHABLE", "TIMEOUT,502,UNREACHABLE"})
    void dockerFailuresMapToStatusAndAuditFailure(DockerErrorCode code, int http, String apiCode) throws Exception {
        String host = localHostId();
        doThrow(new DockerConnectionException(code, "boom", null)).when(volumes).removeVolume(any(), any(), any());
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/volumes/v1", host)))
                .andExpect(status().is(http))
                .andExpect(jsonPath("$.code").value(apiCode));
        assertThat(audit("VOLUME_DELETE")).containsExactly("FAILURE:v1");
    }

    @Test
    void adminSeesTheRawCodeOfACollapsedFailure() throws Exception {
        String host = localHostId();
        doThrow(new DockerConnectionException(DockerErrorCode.TIMEOUT, "boom", null)).when(volumes).removeVolume(any(), any(), any());
        mvc.perform(login("admin").write(delete("/api/hosts/{h}/volumes/v1", host)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("TIMEOUT"));
        assertThat(audit("VOLUME_DELETE")).containsExactly("FAILURE:v1");
    }

    @Test
    void unknownHostIs404() throws Exception {
        localHostId();
        mvc.perform(login("admin").write(delete("/api/hosts/{h}/images/abc", UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    @Test
    void viewerIs403AndNothingIsRemoved() throws Exception {
        String host = localHostId();
        Login viewer = login("viewer");
        for (String r : new String[]{"containers", "images", "volumes", "networks"}) {
            mvc.perform(viewer.write(delete("/api/hosts/{h}/{r}/x1", host, r))).andExpect(status().isForbidden());
        }
        verify(containers, never()).removeContainer(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(volumes, never()).removeVolume(any(), any(), any());
    }

    @Test
    void csrfTokenIsRequired() throws Exception {
        String host = localHostId();
        mvc.perform(delete("/api/hosts/{h}/containers/c1", host).session(login("admin").session()))
                .andExpect(status().isForbidden());
        verify(containers, never()).removeContainer(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        String host = localHostId();
        var csrf = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/me"))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(delete("/api/hosts/{h}/containers/c1", host).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource({"..%2Fetc", "-leading", "a%20b", "a;b"})
    void unsafeIdsAre400(String bad) throws Exception {
        String host = localHostId();
        mvc.perform(login("admin").write(delete("/api/hosts/{h}/volumes/" + bad, host)))
                .andExpect(status().is4xxClientError());
        verify(volumes, never()).removeVolume(any(), any(), any());
    }

    @Test
    void invalidIdIs400WithCode() throws Exception {
        String host = localHostId();
        mvc.perform(login("admin").write(delete("/api/hosts/{h}/volumes/-bad", host)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESOURCE_ID"));
    }

    @Test
    void bareDigestReferenceIsAcceptedAndPassedToTheDaemon() throws Exception {
        String host = localHostId();
        String ref = "nginx@sha256:" + "a".repeat(64);
        mvc.perform(login("operator").write(delete("/api/hosts/{h}/images/{ref}", host, ref)))
                .andExpect(status().isNoContent());
        verify(images).removeImage(any(), any(), eq(ref));
    }

    @Test
    void malformedDigestReferencesAre400AndNeverReachTheDaemon() throws Exception {
        String host = localHostId();
        String hex = "a".repeat(64);
        for (String bad : List.of("nginx@sha256:xyz", "nginx@sha256:" + hex.substring(1), "@sha256:" + hex, "nginx@")) {
            mvc.perform(login("admin").write(delete("/api/hosts/{h}/images/{ref}", host, bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_RESOURCE_ID"));
        }
        verify(images, never()).removeImage(any(), any(), any());
    }
}
