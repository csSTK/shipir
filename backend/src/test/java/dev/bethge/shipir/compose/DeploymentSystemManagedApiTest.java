package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A system-managed deployment (the managed proxy) holds ADMIN-only secrets in its env: the generic read endpoints
 * (env, detail with YAML) answer 403 for OPERATOR and VIEWER, ADMIN keeps access, other deployments are unaffected.
 */
class DeploymentSystemManagedApiTest extends DeploymentTestBase {

    private static final String SECRET = "cf-dns-token-s3cr3t";
    private static final String ENV = "[{\"key\":\"CF_DNS_API_TOKEN\",\"value\":\"" + SECRET + "\"}]";

    private String managed;
    private String ordinary;

    @BeforeEach
    void deployments() throws Exception {
        Login admin = login("admin");
        managed = create(admin, "shipir-proxy");
        ordinary = create(admin, "web");
        jdbc.update("UPDATE deployments SET system_managed = TRUE WHERE id = ?", UUID.fromString(managed));
    }

    private String create(Login l, String name) throws Exception {
        String body = mvc.perform(l.write(post(url()).contentType(APPLICATION_JSON).content(createJson(name, YAML, ENV))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void envOfASystemManagedDeploymentIsForbiddenForOperatorAndViewer() throws Exception {
        for (String role : List.of("operator", "viewer")) {
            mvc.perform(get(url() + "/" + managed + "/env").session(login(role).session()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(content().string(not(containsString(SECRET))));
        }
    }

    @Test
    void detailAndYamlOfASystemManagedDeploymentAreForbiddenForOperatorAndViewer() throws Exception {
        for (String role : List.of("operator", "viewer")) {
            mvc.perform(get(url() + "/" + managed).session(login(role).session()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.yaml").doesNotExist())
                    .andExpect(jsonPath("$.envKeys").doesNotExist());
        }
    }

    @Test
    void adminStillReadsEnvAndDetailOfASystemManagedDeployment() throws Exception {
        Login admin = login("admin");
        mvc.perform(get(url() + "/" + managed + "/env").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].value").value(SECRET));
        mvc.perform(get(url() + "/" + managed).session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yaml").value(YAML))
                .andExpect(jsonPath("$.envKeys[0]").value("CF_DNS_API_TOKEN"));
    }

    @Test
    void theListStillShowsTheSystemManagedDeploymentSoTheUiCanLabelIt() throws Exception {
        mvc.perform(get(url()).session(login("operator").session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.name=='shipir-proxy')].yaml").isEmpty());
    }

    @Test
    void ordinaryDeploymentsStayReadableForOperator() throws Exception {
        Login op = login("operator");
        mvc.perform(get(url() + "/" + ordinary + "/env").session(op.session())).andExpect(status().isOk());
        mvc.perform(get(url() + "/" + ordinary).session(op.session())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT system_managed FROM deployments WHERE id = ?::uuid", Boolean.class,
                ordinary)).isFalse();
    }

    @Test
    void versionEndpointsOfASystemManagedDeploymentAreForbiddenForNonAdmins() throws Exception {
        Login admin = login("admin");
        String body = mvc.perform(get(url() + "/" + managed + "/versions").session(admin.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String versionId = JsonPath.read(body, "$[0].id");
        mvc.perform(get(url() + "/" + managed + "/versions/" + versionId).session(admin.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.yaml").value(YAML));
        for (String role : List.of("operator", "viewer")) {
            Login l = login(role);
            mvc.perform(get(url() + "/" + managed + "/versions").session(l.session()))
                    .andExpect(status().isForbidden());
            mvc.perform(get(url() + "/" + managed + "/versions/" + versionId).session(l.session()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.yaml").doesNotExist());
        }
        String ordinaryBody = mvc.perform(get(url() + "/" + ordinary + "/versions").session(login("operator").session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(ordinaryBody, "$[0].id")).isNotBlank();
    }
}
