package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/hosts/{h}/ports/free against a stubbed ContainerService. */
class HostPortsApiTest extends HostApiTestBase {

    @MockitoBean
    ContainerService containers;

    @Override
    protected boolean autoGrantHosts() {
        return false;
    }

    private String localHostId() {
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        return jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    private static ContainerDto container(String state, String... ports) {
        return new ContainerDto("c", "c", "img", state, state, null, null, List.of(ports), null, null);
    }

    private void stubContainers() {
        when(containers.listContainers(any(), any())).thenReturn(List.of(
                container("running", "8080:80/tcp", "5353:53/udp"),
                container("created", "9000:9000/tcp"),
                container("exited", "8081:80/tcp")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "operator", "admin"})
    void everyRoleGetsUsedPortsAndSuggestions(String user) throws Exception {
        String host = localHostId();
        grantAllHosts();
        stubContainers();
        mvc.perform(get("/api/hosts/{h}/ports/free", host).param("preferred", "8080,8081")
                        .session(login(user).session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used.length()").value(3))
                .andExpect(jsonPath("$.suggestions[0].preferred").value(8080))
                .andExpect(jsonPath("$.suggestions[0].free").value(8081))
                .andExpect(jsonPath("$.suggestions[1].preferred").value(8081))
                .andExpect(jsonPath("$.suggestions[1].free").value(8082));
    }

    @Test
    void deploymentOnlyGrantSeesNoForeignPortsButSuggestionsSkipThem() throws Exception {
        String host = localHostId();
        stubContainers();
        UUID dep = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'd', ?::uuid, 'x', id FROM users WHERE username = 'admin' RETURNING id", UUID.class, host);
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, 'OPERATE' "
                + "FROM users WHERE username = 'operator'", dep);
        mvc.perform(get("/api/hosts/{h}/ports/free", host).param("preferred", "8080")
                        .session(login("operator").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used.length()").value(0))
                .andExpect(jsonPath("$.used[?(@.port == 8080)]").isEmpty())
                .andExpect(jsonPath("$.suggestions[0].free").value(8081));
    }

    @Test
    void exitedContainersDoNotHoldPorts() throws Exception {
        String host = localHostId();
        stubContainers();
        mvc.perform(get("/api/hosts/{h}/ports/free", host).param("preferred", "9000,9001")
                        .session(login("admin").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions[0].free").value(9001))
                .andExpect(jsonPath("$.suggestions[1].free").value(9002));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536", "abc", "80,,81"})
    void invalidPortsAre400(String preferred) throws Exception {
        String host = localHostId();
        mvc.perform(get("/api/hosts/{h}/ports/free", host).param("preferred", preferred)
                        .session(login("admin").session()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooManyPortsAre400() throws Exception {
        String host = localHostId();
        String raw = java.util.stream.IntStream.rangeClosed(1, 51).mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        mvc.perform(get("/api/hosts/{h}/ports/free", host).param("preferred", raw)
                        .session(login("admin").session()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invisibleHostIs404() throws Exception {
        String host = localHostId();
        mvc.perform(get("/api/hosts/{h}/ports/free", host).param("preferred", "80")
                        .session(login("viewer").session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownHostIs404() throws Exception {
        localHostId(); // the setup gate needs at least one environment
        mvc.perform(get("/api/hosts/{h}/ports/free", UUID.randomUUID()).session(login("admin").session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/api/hosts/{h}/ports/free", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
}
