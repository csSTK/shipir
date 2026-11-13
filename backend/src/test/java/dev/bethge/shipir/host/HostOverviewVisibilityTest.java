package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.DockerSystemService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.DockerSystemInfoDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The overview counts follow the same visibility as the list endpoints: a user who sees a host only through
 * deployment grants must not learn host-wide container, image or volume counts (shipr-kqbc.65).
 */
class HostOverviewVisibilityTest extends HostApiTestBase {

    @MockitoBean
    DockerSystemService docker;
    @MockitoBean
    ContainerService containers;
    @MockitoBean
    ImageService images;
    @MockitoBean
    NetworkService networks;
    @MockitoBean
    VolumeService volumes;

    private UUID host;

    @Override
    protected boolean autoGrantHosts() {
        return false;
    }

    @BeforeEach
    void host() {
        SetupGateFixture.resetWithAdminHost(jdbc, "local");
        host = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        when(docker.info(any(), any())).thenReturn(new DockerSystemInfoDto("27.0.1", 4, 8_000_000_000L, 3, 2, 1, 1, 7, 5));
        when(containers.listContainers(any(), any())).thenReturn(List.of(
                c("c-web", "running", "healthy", "shop"),
                c("c-web2", "exited", null, "shop"),
                c("c-other", "running", "unhealthy", "other"),
                c("c-plain", "running", null, null)));
    }

    private static ContainerDto c(String id, String state, String health, String project) {
        return new ContainerDto(id, id, "nginx:1", state, state, health, null, List.of(), project, project);
    }

    private void grantShopDeployment(String user) {
        UUID dep = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'shop', ?, 'x', id FROM users WHERE username = 'admin' RETURNING id", UUID.class, host);
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, 'VIEW' "
                + "FROM users WHERE username = ?", dep, user);
    }

    @Test
    void deploymentOnlyGrantSeesOnlyCountsOfItsOwnContainersAndNoHostWideImagesOrVolumes() throws Exception {
        grantShopDeployment("viewer");
        mvc.perform(get("/api/hosts/overview").session(login("viewer").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("UP"))
                .andExpect(jsonPath("$[0].containers.running").value(1))
                .andExpect(jsonPath("$[0].containers.stopped").value(1))
                .andExpect(jsonPath("$[0].containers.healthy").value(1))
                .andExpect(jsonPath("$[0].containers.unhealthy").value(0))
                .andExpect(jsonPath("$[0].images").doesNotExist())
                .andExpect(jsonPath("$[0].volumes").doesNotExist())
                .andExpect(jsonPath("$[0].dockerVersion").doesNotExist())
                .andExpect(jsonPath("$[0].cpus").doesNotExist())
                .andExpect(jsonPath("$[0].memoryBytes").doesNotExist());
    }

    @Test
    void deploymentOnlyGrantWithoutAnyVisibleContainerGetsZeros() throws Exception {
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'unrelated', ?, 'x', id FROM users WHERE username = 'admin'", host);
        grantShopDeployment("operator");
        when(containers.listContainers(any(), any())).thenReturn(List.of(c("c-other", "running", "unhealthy", "other")));
        mvc.perform(get("/api/hosts/overview").session(login("operator").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].containers.running").value(0))
                .andExpect(jsonPath("$[0].containers.stopped").value(0))
                .andExpect(jsonPath("$[0].containers.unhealthy").value(0))
                .andExpect(jsonPath("$[0].images").doesNotExist())
                .andExpect(jsonPath("$[0].volumes").doesNotExist());
    }

    @Test
    void hostGrantAndAdminStillSeeHostWideCounts() throws Exception {
        grantHost("viewer", host, "VIEW");
        for (String user : new String[]{"viewer", "admin"}) {
            mvc.perform(get("/api/hosts/overview").session(login(user).session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].containers.running").value(3))
                    .andExpect(jsonPath("$[0].containers.stopped").value(2))
                    .andExpect(jsonPath("$[0].images").value(7))
                    .andExpect(jsonPath("$[0].volumes").value(5))
                    .andExpect(jsonPath("$[0].dockerVersion").value("27.0.1"))
                    .andExpect(jsonPath("$[0].cpus").value(4))
                    .andExpect(jsonPath("$[0].memoryBytes").value(8_000_000_000L));
        }
    }
}
