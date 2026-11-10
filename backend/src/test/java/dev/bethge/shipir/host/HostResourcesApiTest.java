package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Resource endpoints against the real local Docker socket. */
class HostResourcesApiTest extends HostApiTestBase {

    private static final String[] RESOURCES = {"deployments", "containers", "images", "networks", "volumes"};

    private String localHostId() {
        SetupGateFixture.resetWithAdminHost(jdbc, "local");
        return jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "operator", "admin"})
    void everyRoleGetsJsonListsForAllFiveResources(String user) throws Exception {
        String id = localHostId();
        Login login = login(user);
        for (String resource : RESOURCES) {
            mvc.perform(get("/api/hosts/{id}/{r}", id, resource).session(login.session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }
    }

    @Test
    void networksOfLocalDaemonContainDefaultBridge() throws Exception {
        String id = localHostId();
        mvc.perform(get("/api/hosts/{id}/networks", id).session(login("viewer").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'bridge')]").isNotEmpty());
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        String id = localHostId();
        for (String resource : RESOURCES) {
            mvc.perform(get("/api/hosts/{id}/{r}", id, resource)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void unknownHostIs404() throws Exception {
        Login viewer = login("viewer");
        for (String resource : RESOURCES) {
            mvc.perform(get("/api/hosts/{id}/{r}", UUID.randomUUID(), resource).session(viewer.session()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
        }
    }
}
