package dev.bethge.shipir.compose;

import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Removal of unmanaged Compose projects: {@code docker compose -p <name> down} through a stubbed execution. */
class UnmanagedDeploymentApiTest extends DeploymentTestBase {

    private String url(String name) {
        return "/api/hosts/" + hostId + "/deployments/" + name;
    }

    @BeforeEach
    void stubProjectDown() {
        when(execution.executeProject(anyString(), any(), any(), any()))
                .thenReturn(new ComposeProcessResult(0, false, List.of()));
    }

    private List<String> audit() {
        return jdbc.queryForList("SELECT action || ':' || outcome || ':' || coalesce(target_id, '') FROM audit_log "
                + "WHERE action = 'DEPLOYMENT_REMOVE_UNMANAGED' ORDER BY occurred_at", String.class);
    }

    @Test
    void removesUnmanagedProjectWithPlainDownAndAudits() throws Exception {
        mvc.perform(login("operator").write(delete(url("legacy")))).andExpect(status().isNoContent());

        verify(execution).executeProject(eq("legacy"), eq(ComposeCommand.DOWN), any(), any());
        assertThat(audit()).containsExactly("DEPLOYMENT_REMOVE_UNMANAGED:SUCCESS:legacy");
    }

    @Test
    void removeVolumesFlagUsesDownWithVolumes() throws Exception {
        mvc.perform(login("admin").write(delete(url("legacy") + "?removeVolumes=true"))).andExpect(status().isNoContent());

        verify(execution).executeProject(eq("legacy"), eq(ComposeCommand.DOWN_VOLUMES), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bad_Name", "-x", "UPPER", "a.b", "a,b"})
    void rejectsMaliciousProjectNamesWithoutRunningAnything(String name) throws Exception {
        mvc.perform(login("admin").write(delete(url(name))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEPLOYMENT_NAME"));

        verify(execution, never()).executeProject(any(), any(), any(), any());
    }

    /** Encoded separators, dot segments, NULs and shell metacharacters never reach the service: the HTTP firewall answers 400. */
    @ParameterizedTest
    @ValueSource(strings = {"a%20b", "%3Brm%20-rf", "%2e%2e", "a%24%28id%29", "x%00y", "..%2Fetc", "a%2Fb"})
    void httpFirewallRejectsEncodedAndShellLikeNames(String name) throws Exception {
        mvc.perform(login("admin").write(delete(url(name)))).andExpect(status().is4xxClientError());

        verify(execution, never()).executeProject(any(), any(), any(), any());
    }

    @Test
    void managedProjectIsRefusedWith409() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/hosts/" + hostId + "/managed-deployments"))
                .contentType("application/json").content(createJson("managed", YAML, null)))
                .andExpect(status().isCreated());

        mvc.perform(admin.write(delete(url("managed"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANAGED_DEPLOYMENT"));

        verify(execution, never()).executeProject(any(), any(), any(), any());
        assertThat(audit()).containsExactly("DEPLOYMENT_REMOVE_UNMANAGED:FAILURE:managed");
    }

    @Test
    void failingDownReturns502WithOutputTail() throws Exception {
        when(execution.executeProject(anyString(), any(), any(), any()))
                .thenReturn(new ComposeProcessResult(1, false, List.of("", "cannot reach daemon")));

        mvc.perform(login("admin").write(delete(url("legacy"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("COMPOSE_DOWN_FAILED"))
                .andExpect(jsonPath("$.details[0]").value("cannot reach daemon"));
        assertThat(audit()).containsExactly("DEPLOYMENT_REMOVE_UNMANAGED:FAILURE:legacy");
    }

    @Test
    void viewerIsForbiddenAndUnknownHostIs404() throws Exception {
        mvc.perform(login("viewer").write(delete(url("legacy")))).andExpect(status().isForbidden());
        mvc.perform(login("operator").write(delete("/api/hosts/" + UUID.randomUUID() + "/deployments/legacy")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));

        verify(execution, never()).executeProject(any(), any(), any(), any());
    }
}
