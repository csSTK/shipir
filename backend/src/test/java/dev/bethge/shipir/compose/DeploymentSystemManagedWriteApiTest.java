package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The managed proxy deployment (system-managed) is ADMIN-only on every write path of the generic deployment
 * endpoints: an OPERATOR with host OPERATE gets 403 and nothing runs; ADMIN and ordinary deployments are unaffected.
 */
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class DeploymentSystemManagedWriteApiTest extends DeploymentTestBase {

    private String managed;
    private String ordinary;
    private String versionId;

    @BeforeEach
    void deployments() throws Exception {
        Login admin = login("admin");
        managed = create(admin, "shipir-proxy");
        ordinary = create(admin, "web");
        jdbc.update("UPDATE deployments SET system_managed = TRUE WHERE id = ?", UUID.fromString(managed));
        String body = mvc.perform(get(url() + "/" + managed + "/versions").session(admin.session()))
                .andReturn().getResponse().getContentAsString();
        versionId = JsonPath.read(body, "$[0].id");
        commands.clear();
    }

    private String create(Login l, String name) throws Exception {
        String body = mvc.perform(l.write(post(url()).contentType(APPLICATION_JSON).content(createJson(name, YAML, null))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** Every write endpoint of the generic deployment API, as a request builder for a deployment id. */
    private Map<String, Function<String, MockHttpServletRequestBuilder>> writes() {
        Map<String, Function<String, MockHttpServletRequestBuilder>> m = new LinkedHashMap<>();
        m.put("update", id -> put(url() + "/" + id).contentType(APPLICATION_JSON).content(updateJson(YAML, null)));
        m.put("delete", id -> delete(url() + "/" + id));
        m.put("deploy", id -> post(url() + "/" + id + "/deploy"));
        m.put("pull-update", id -> post(url() + "/" + id + "/pull-update"));
        m.put("start", id -> post(url() + "/" + id + "/start"));
        m.put("stop", id -> post(url() + "/" + id + "/stop"));
        m.put("service-restart", id -> post(url() + "/" + id + "/services/web/restart"));
        m.put("service-pull", id -> post(url() + "/" + id + "/services/web/pull"));
        m.put("service-up", id -> post(url() + "/" + id + "/services/web/up"));
        m.put("services", id -> get(url() + "/" + id + "/services"));
        m.put("profiles", id -> put(url() + "/" + id + "/profiles").contentType(APPLICATION_JSON)
                .content("{\"profiles\":[]}"));
        m.put("git-redeploy", id -> post(url() + "/" + id + "/git/redeploy"));
        m.put("git-auto-update", id -> put(url() + "/" + id + "/git/auto-update").contentType(APPLICATION_JSON)
                .content("{\"enabled\":false}"));
        m.put("image-auto-update", id -> put(url() + "/" + id + "/image-auto-update").contentType(APPLICATION_JSON)
                .content("{\"enabled\":false}"));
        m.put("rollback", id -> post(url() + "/" + id + "/versions/" + versionId + "/rollback"));
        m.put("webhook-status", id -> get(url() + "/" + id + "/webhook"));
        m.put("webhook-enable", id -> put(url() + "/" + id + "/webhook"));
        m.put("webhook-rotate", id -> post(url() + "/" + id + "/webhook/rotate"));
        m.put("webhook-disable", id -> delete(url() + "/" + id + "/webhook"));
        m.put("uptime-list", id -> get(url() + "/" + id + "/uptime-checks"));
        return m;
    }

    @Test
    void operatorGets403OnEveryWritePathOfASystemManagedDeployment() throws Exception {
        Login op = login("operator");
        for (var e : writes().entrySet()) {
            mvc.perform(op.write(e.getValue().apply(managed)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            assertThat(commands).as("nothing ran for " + e.getKey()).isEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments WHERE id = ?::uuid", Integer.class, managed))
                .isEqualTo(1);
    }

    @Test
    void operatorWithADeploymentOperateGrantIsForbiddenToo() throws Exception {
        jdbc.update("DELETE FROM permissions");
        grantDeployment("operator", UUID.fromString(managed), "OPERATE");
        Login op = login("operator");
        mvc.perform(op.write(post(url() + "/" + managed + "/deploy"))).andExpect(status().isForbidden());
        mvc.perform(op.write(delete(url() + "/" + managed))).andExpect(status().isForbidden());
        assertThat(commands).isEmpty();
    }

    @Test
    void adminKeepsFullAccessToASystemManagedDeployment() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(put(url() + "/" + managed).contentType(APPLICATION_JSON).content(updateJson(YAML, null))))
                .andExpect(status().isOk());
        mvc.perform(admin.write(put(url() + "/" + managed + "/profiles").contentType(APPLICATION_JSON)
                .content("{\"profiles\":[]}"))).andExpect(status().isOk());
        mvc.perform(admin.write(put(url() + "/" + managed + "/webhook"))).andExpect(status().isOk());
        mvc.perform(admin.write(post(url() + "/" + managed + "/deploy"))).andExpect(status().isAccepted());
        mvc.perform(admin.write(delete(url() + "/" + managed))).andExpect(status().isNoContent());
    }

    @Test
    void ordinaryDeploymentsStayOperableForOperator() throws Exception {
        Login op = login("operator");
        mvc.perform(op.write(put(url() + "/" + ordinary).contentType(APPLICATION_JSON).content(updateJson(YAML, null))))
                .andExpect(status().isOk());
        mvc.perform(op.write(put(url() + "/" + ordinary + "/profiles").contentType(APPLICATION_JSON)
                .content("{\"profiles\":[]}"))).andExpect(status().isOk());
        mvc.perform(op.write(post(url() + "/" + ordinary + "/deploy"))).andExpect(status().isAccepted());
    }

    @Test
    void adoptAndUnmanagedRemoveCannotTouchTheSystemManagedProjectByName() throws Exception {
        Login op = login("operator");
        String hosts = "/api/hosts/" + hostId + "/deployments/shipir-proxy";
        mvc.perform(op.write(delete(hosts))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANAGED_DEPLOYMENT"));
        mvc.perform(op.write(post(hosts + "/adopt")).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEPLOYMENT_NAME_TAKEN"));
        assertThat(commands).isEmpty();
    }

    @Test
    void summaryAndDetailExposeTheSystemManagedFlag() throws Exception {
        mvc.perform(get(url()).session(login("operator").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='shipir-proxy')].systemManaged").value(true))
                .andExpect(jsonPath("$[?(@.name=='web')].systemManaged").value(false));
        mvc.perform(get(url() + "/" + managed).session(login("admin").session()))
                .andExpect(jsonPath("$.systemManaged").value(true));
        mvc.perform(get(url() + "/" + ordinary).session(login("admin").session()))
                .andExpect(jsonPath("$.systemManaged").value(false));
    }
}
