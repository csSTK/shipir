package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Host list, overview, resources, deletes and container actions respect per-host grants (non-visible = 404, VIEW = 403). */
class HostPermissionTest extends HostApiTestBase {

    @Override
    protected boolean autoGrantHosts() {
        return false;
    }

    private UUID visible;
    private UUID hidden;

    @BeforeEach
    void hosts() {
        SetupGateFixture.resetWithAdminHost(jdbc, "visible");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, host, port, owner_user_id) "
                + "SELECT 'hidden', 'TCP', 'hidden.local', 2375, id FROM users WHERE username = 'admin'");
        visible = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'visible'", UUID.class);
        hidden = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'hidden'", UUID.class);
    }

    @Test
    void listAndOverviewAreFilteredToGrantedHosts() throws Exception {
        grantHost("operator", visible, "VIEW");
        Login op = login("operator");
        mvc.perform(get("/api/hosts").session(op.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("visible"));
        mvc.perform(get("/api/hosts/overview").session(op.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("visible"));

        mvc.perform(get("/api/hosts").session(login("admin").session())).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/hosts/overview").session(login("admin").session()))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void userWithoutGrantsSeesNoHosts() throws Exception {
        Login viewer = login("viewer");
        mvc.perform(get("/api/hosts").session(viewer.session())).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/hosts/overview").session(viewer.session())).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void hiddenHostIs404ForEveryReadWriteAndAction() throws Exception {
        grantHost("operator", visible, "OPERATE");
        Login op = login("operator");
        for (String r : new String[]{"deployments", "containers", "images", "networks", "volumes"}) {
            mvc.perform(get("/api/hosts/{h}/{r}", hidden, r).session(op.session()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
        }
        mvc.perform(op.write(delete("/api/hosts/{h}/volumes/v1", hidden))).andExpect(status().isNotFound());
        mvc.perform(op.write(delete("/api/hosts/{h}/containers/c1", hidden))).andExpect(status().isNotFound());
        mvc.perform(op.write(post("/api/hosts/{h}/containers/c1/start", hidden))).andExpect(status().isNotFound());
        mvc.perform(op.write(post("/api/hosts/{h}/containers/c1/kill", hidden))).andExpect(status().isNotFound());
        // the visible host is reachable (not found refers to the container, not the host)
        mvc.perform(get("/api/hosts/{h}/images", visible).session(op.session())).andExpect(status().isOk());
        mvc.perform(op.write(post("/api/hosts/{h}/containers/doesnotexist1/start", visible)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void viewGrantBlocksDeletesAndContainerActionsWith403() throws Exception {
        grantHost("operator", visible, "VIEW");
        Login op = login("operator");
        mvc.perform(get("/api/hosts/{h}/containers", visible).session(op.session())).andExpect(status().isOk());
        mvc.perform(op.write(delete("/api/hosts/{h}/volumes/v1", visible))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(op.write(delete("/api/hosts/{h}/images/i1", visible))).andExpect(status().isForbidden());
        mvc.perform(op.write(delete("/api/hosts/{h}/networks/n1", visible))).andExpect(status().isForbidden());
        mvc.perform(op.write(delete("/api/hosts/{h}/containers/c1", visible))).andExpect(status().isForbidden());
        for (String a : new String[]{"start", "stop", "restart", "pause", "unpause", "kill"}) {
            mvc.perform(op.write(post("/api/hosts/{h}/containers/c1/{a}", visible, a))).andExpect(status().isForbidden());
        }
    }

    @Test
    void deploymentOnlyGrantShowsHostButNotItsWholeResources() throws Exception {
        UUID dep = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'd', ?, 'x', id FROM users WHERE username = 'admin' RETURNING id", UUID.class, hidden);
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, 'OPERATE' "
                + "FROM users WHERE username = 'operator'", dep);
        Login op = login("operator");
        mvc.perform(get("/api/hosts").session(op.session())).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("hidden"));
        mvc.perform(get("/api/hosts/{h}/images", hidden).session(op.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/hosts/{h}/volumes", hidden).session(op.session())).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(op.write(delete("/api/hosts/{h}/volumes/v1", hidden))).andExpect(status().isForbidden());
        mvc.perform(get("/api/hosts/{h}/images", visible).session(op.session())).andExpect(status().isNotFound());
    }

    @Test
    void hostWritesStayAdminOnly() throws Exception {
        grantHost("operator", visible, "OPERATE");
        Login op = login("operator");
        mvc.perform(op.write(delete("/api/hosts/{h}", visible))).andExpect(status().isForbidden());
    }
}
