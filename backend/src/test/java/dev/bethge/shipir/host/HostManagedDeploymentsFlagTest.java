package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.DeploymentSummaryDto;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The live deployment list marks projects that Shipir manages (managed flag + id). */
class HostManagedDeploymentsFlagTest extends HostApiTestBase {

    @MockitoBean
    ContainerService containers;

    @Test
    void liveDeploymentsCarryManagedFlagAndId() throws Exception {
        UUID owner = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", UUID.class);
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) VALUES ('h', 'LOCAL', ?)", owner);
        UUID host = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        jdbc.update("DELETE FROM deployments");
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by) VALUES ('mine', ?, 'x', ?)",
                host, owner);
        UUID deploymentId = jdbc.queryForObject("SELECT id FROM deployments", UUID.class);
        when(containers.listDeployments(any(), any())).thenReturn(List.of(
                new DeploymentSummaryDto("mine", 2, 2, DeploymentSummaryDto.Status.RUNNING, "/srv/mine"),
                new DeploymentSummaryDto("foreign", 1, 0, DeploymentSummaryDto.Status.STOPPED, null)));

        mvc.perform(get("/api/hosts/{id}/deployments", host).session(login("viewer").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("mine"))
                .andExpect(jsonPath("$[0].managed").value(true))
                .andExpect(jsonPath("$[0].managedDeploymentId").value(deploymentId.toString()))
                .andExpect(jsonPath("$[0].containerCount").value(2))
                .andExpect(jsonPath("$[1].name").value("foreign"))
                .andExpect(jsonPath("$[1].managed").value(false))
                .andExpect(jsonPath("$[1].managedDeploymentId").doesNotExist());
        jdbc.update("DELETE FROM deployments");
    }
}
