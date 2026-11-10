package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Container lifecycle POSTs against stubbed Docker services: status mapping, roles, CSRF, timeout, audit rows. */
class HostContainerActionsTest extends HostApiTestBase {

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
        SetupGateFixture.resetWithAdminHost(jdbc, "local");
        return jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    private List<String> audit(String action) {
        return jdbc.queryForList("SELECT outcome || ':' || coalesce(target_id, '') FROM audit_log WHERE action = ?",
                String.class, action);
    }

    @ParameterizedTest
    @CsvSource({"start,CONTAINER_START", "stop,CONTAINER_STOP", "restart,CONTAINER_RESTART",
            "pause,CONTAINER_PAUSE", "unpause,CONTAINER_UNPAUSE", "kill,CONTAINER_KILL"})
    void adminAndOperatorGet204AndOneAuditRow(String action, String auditAction) throws Exception {
        String host = localHostId();
        mvc.perform(login("operator").write(post("/api/hosts/{h}/containers/abc123/{a}", host, action)))
                .andExpect(status().isNoContent());
        mvc.perform(login("admin").write(post("/api/hosts/{h}/containers/abc123/{a}", host, action)))
                .andExpect(status().isNoContent());
        assertThat(audit(auditAction)).containsExactly("SUCCESS:abc123", "SUCCESS:abc123");
        verifyCalled(action, 10);
    }

    private void verifyCalled(String action, int timeout) {
        switch (action) {
            case "start" -> verify(containers, org.mockito.Mockito.times(2)).startContainer(any(), any(), eq("abc123"));
            case "stop" -> verify(containers, org.mockito.Mockito.times(2))
                    .stopContainer(any(), any(), eq("abc123"), eq(timeout));
            case "restart" -> verify(containers, org.mockito.Mockito.times(2))
                    .restartContainer(any(), any(), eq("abc123"), eq(timeout));
            case "pause" -> verify(containers, org.mockito.Mockito.times(2)).pauseContainer(any(), any(), eq("abc123"));
            case "unpause" -> verify(containers, org.mockito.Mockito.times(2)).unpauseContainer(any(), any(), eq("abc123"));
            default -> verify(containers, org.mockito.Mockito.times(2)).killContainer(any(), any(), eq("abc123"));
        }
    }

    @ParameterizedTest
    @CsvSource({"stop,0", "stop,300", "restart,45"})
    void timeoutIsPassedThrough(String action, int timeout) throws Exception {
        String host = localHostId();
        mvc.perform(login("admin").write(post("/api/hosts/{h}/containers/c1/{a}?timeout={t}", host, action, timeout)))
                .andExpect(status().isNoContent());
        if (action.equals("stop")) {
            verify(containers).stopContainer(any(), any(), eq("c1"), eq(timeout));
        } else {
            verify(containers).restartContainer(any(), any(), eq("c1"), eq(timeout));
        }
    }

    @ParameterizedTest
    @CsvSource({"stop,-1", "stop,301", "restart,abc", "restart,1.5"})
    void invalidTimeoutIs400(String action, String timeout) throws Exception {
        String host = localHostId();
        mvc.perform(login("admin").write(post("/api/hosts/{h}/containers/c1/{a}?timeout={t}", host, action, timeout)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        verify(containers, never()).stopContainer(any(), any(), any(), anyInt());
        verify(containers, never()).restartContainer(any(), any(), any(), anyInt());
    }

    @ParameterizedTest
    @CsvSource({"NOT_FOUND,404,RESOURCE_NOT_FOUND", "INVALID_STATE,409,INVALID_CONTAINER_STATE",
            "UNREACHABLE,502,UNREACHABLE", "TIMEOUT,502,UNREACHABLE", "PERMISSION_DENIED,502,UNREACHABLE"})
    void dockerFailuresMapToStatusAndAuditFailure(DockerErrorCode code, int http, String apiCode) throws Exception {
        String host = localHostId();
        doThrow(new DockerConnectionException(code, "boom", null)).when(containers).pauseContainer(any(), any(), any());
        mvc.perform(login("operator").write(post("/api/hosts/{h}/containers/c1/pause", host)))
                .andExpect(status().is(http))
                .andExpect(jsonPath("$.code").value(apiCode));
        assertThat(audit("CONTAINER_PAUSE")).containsExactly("FAILURE:c1");
    }

    @Test
    void adminSeesTheRawCodeOfACollapsedFailure() throws Exception {
        String host = localHostId();
        doThrow(new DockerConnectionException(DockerErrorCode.TIMEOUT, "boom", null)).when(containers).pauseContainer(any(), any(), any());
        mvc.perform(login("admin").write(post("/api/hosts/{h}/containers/c1/pause", host)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("TIMEOUT"));
        assertThat(audit("CONTAINER_PAUSE")).containsExactly("FAILURE:c1");
    }

    @Test
    void unknownHostIs404() throws Exception {
        localHostId();
        mvc.perform(login("admin").write(post("/api/hosts/{h}/containers/c1/start", UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    @Test
    void invalidContainerIdIs400() throws Exception {
        String host = localHostId();
        mvc.perform(login("admin").write(post("/api/hosts/{h}/containers/-bad/kill", host)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESOURCE_ID"));
        verifyNoInteractions(containers);
    }

    @Test
    void viewerIs403AndNothingRuns() throws Exception {
        String host = localHostId();
        Login viewer = login("viewer");
        for (String a : new String[]{"start", "stop", "restart", "pause", "unpause", "kill"}) {
            mvc.perform(viewer.write(post("/api/hosts/{h}/containers/c1/{a}", host, a))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(containers);
    }

    @Test
    void csrfTokenIsRequired() throws Exception {
        String host = localHostId();
        mvc.perform(post("/api/hosts/{h}/containers/c1/start", host).session(login("admin").session()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(containers);
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        String host = localHostId();
        var csrf = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/hosts/{h}/containers/c1/start", host).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
    }
}
