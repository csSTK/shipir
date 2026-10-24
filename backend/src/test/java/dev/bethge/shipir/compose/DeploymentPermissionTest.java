package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Per-host / per-deployment grants on managed deployments, runs, versions, Git endpoints and config files. */
class DeploymentPermissionTest extends DeploymentTestBase {

    private UUID granted;
    private UUID other;
    private UUID otherHost;

    @BeforeEach
    void deployments() throws Exception {
        // the base class gave operator/viewer host grants; these tests work with deployment grants only
        jdbc.update("DELETE FROM permissions");
        Login admin = login("admin");
        granted = create(admin, "granted");
        other = create(admin, "other");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, host, port, owner_user_id) "
                + "SELECT 'Second host', 'TCP', 'second.local', 2375, id FROM users WHERE username = 'admin'");
        otherHost = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'Second host'", UUID.class);
    }

    private UUID create(Login admin, String name) throws Exception {
        String body = mvc.perform(admin.write(post(url()).contentType(APPLICATION_JSON)
                        .content(createJson(name, YAML, null))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private String depUrl(UUID id) {
        return url() + "/" + id;
    }

    private MockHttpServletRequestBuilder as(Login l, MockHttpServletRequestBuilder b) {
        return l.write(b);
    }

    @Test
    void userWithoutGrantsSeesNothingAnd404s() throws Exception {
        Login op = login("operator");
        mvc.perform(get(url()).session(op.session())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
        mvc.perform(get(depUrl(granted)).session(op.session())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
        mvc.perform(as(op, post(url()).contentType(APPLICATION_JSON).content(createJson("x", YAML, null))))
                .andExpect(status().isNotFound());
        mvc.perform(as(op, post(depUrl(granted) + "/deploy"))).andExpect(status().isNotFound());
        mvc.perform(get(depUrl(granted) + "/files").session(op.session())).andExpect(status().isNotFound());
        mvc.perform(as(op, delete("/api/hosts/{h}/deployments/foo", hostId))).andExpect(status().isNotFound());
    }

    @Test
    void deploymentGrantShowsOnlyThatDeployment() throws Exception {
        grantDeployment("operator", granted, "OPERATE");
        Login op = login("operator");

        mvc.perform(get(url()).session(op.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(granted.toString()));
        mvc.perform(get(depUrl(granted)).session(op.session())).andExpect(status().isOk());
        mvc.perform(get(depUrl(other)).session(op.session())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_NOT_FOUND"));
        mvc.perform(get(depUrl(other) + "/env").session(op.session())).andExpect(status().isNotFound());
        mvc.perform(get(depUrl(other) + "/versions").session(op.session())).andExpect(status().isNotFound());
        mvc.perform(as(op, post(depUrl(other) + "/deploy"))).andExpect(status().isNotFound());
        mvc.perform(as(op, post(depUrl(other) + "/stop"))).andExpect(status().isNotFound());
        mvc.perform(as(op, delete(depUrl(other)))).andExpect(status().isNotFound());
        mvc.perform(get(depUrl(other) + "/files").session(op.session())).andExpect(status().isNotFound());
        // a deployment grant does not allow host-level writes
        mvc.perform(as(op, post(url()).contentType(APPLICATION_JSON).content(createJson("x", YAML, null))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(as(op, delete("/api/hosts/{h}/deployments/foo", hostId))).andExpect(status().isForbidden());
        // other host stays invisible
        mvc.perform(get("/api/hosts/{h}/managed-deployments", otherHost).session(op.session()))
                .andExpect(status().isNotFound());
        // OPERATE on the granted deployment works
        mvc.perform(as(op, post(depUrl(granted) + "/stop"))).andExpect(status().isOk());
    }

    @Test
    void viewGrantAllowsReadsButBlocksEveryWrite() throws Exception {
        grantDeployment("operator", granted, "VIEW");
        Login op = login("operator");
        String u = depUrl(granted);
        commands.clear();

        mvc.perform(get(u).session(op.session())).andExpect(status().isOk());
        mvc.perform(get(u + "/versions").session(op.session())).andExpect(status().isOk());
        mvc.perform(get(u + "/files").session(op.session())).andExpect(status().isOk());

        mvc.perform(as(op, put(u).contentType(APPLICATION_JSON).content(updateJson(YAML, null))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(as(op, delete(u))).andExpect(status().isForbidden());
        mvc.perform(as(op, post(u + "/deploy"))).andExpect(status().isForbidden());
        mvc.perform(as(op, post(u + "/stop"))).andExpect(status().isForbidden());
        mvc.perform(as(op, post(u + "/git/redeploy"))).andExpect(status().isForbidden());
        mvc.perform(as(op, put(u + "/git/auto-update").contentType(APPLICATION_JSON)
                .content("{\"enabled\":false}"))).andExpect(status().isForbidden());
        mvc.perform(as(op, post(u + "/versions/" + UUID.randomUUID() + "/rollback"))).andExpect(status().isForbidden());
        mvc.perform(as(op, put(u + "/files").param("path", "a.txt").contentType(APPLICATION_JSON)
                .content("{\"content\":\"x\"}"))).andExpect(status().isForbidden());
        mvc.perform(as(op, delete(u + "/files").param("path", "a.txt"))).andExpect(status().isForbidden());
        mvc.perform(as(op, post(u + "/files/restore").param("path", "a.txt").contentType(APPLICATION_JSON)
                .content("{\"backupId\":\"20240101000000000-abcdef12\"}"))).andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(commands).isEmpty();
    }

    @Test
    void hostOperateGrantCoversAllDeploymentsAndCreation() throws Exception {
        grantHost("operator", "OPERATE");
        Login op = login("operator");
        mvc.perform(get(url()).session(op.session())).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(as(op, put(depUrl(other) + "/files").param("path", "a.txt").contentType(APPLICATION_JSON)
                .content("{\"content\":\"x\"}"))).andExpect(status().isOk());
        mvc.perform(as(op, post(url()).contentType(APPLICATION_JSON).content(createJson("fresh", YAML, null))))
                .andExpect(status().isCreated());
    }

    @Test
    void hostViewGrantBlocksCreateButViewerRoleCapsOperateGrant() throws Exception {
        grantHost("operator", "VIEW");
        Login op = login("operator");
        mvc.perform(get(url()).session(op.session())).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(as(op, post(url()).contentType(APPLICATION_JSON).content(createJson("fresh", YAML, null))))
                .andExpect(status().isForbidden());

        // an OPERATE grant never lifts the VIEWER role
        grantHost("viewer", "OPERATE");
        Login viewer = login("viewer");
        mvc.perform(get(depUrl(granted)).session(viewer.session())).andExpect(status().isOk());
        mvc.perform(as(viewer, post(depUrl(granted) + "/deploy"))).andExpect(status().isForbidden());
    }
}
