package dev.bethge.shipir.compose;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Per-row canOperate of the deployments list and the host-level canOperate of the host list (shipr-0qbh.97). */
class DeploymentListCanOperateTest extends DeploymentTestBase {

    private String create() throws Exception {
        String body = mvc.perform(login("admin").write(post(url()).contentType(APPLICATION_JSON)
                        .content(createJson("web", YAML, null))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.id");
    }

    private void expectRow(String user, boolean operate) throws Exception {
        mvc.perform(get(url()).session(login(user).session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].canOperate").value(operate));
    }

    private void expectHost(String user, boolean operate) throws Exception {
        mvc.perform(get("/api/hosts").session(login(user).session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].canOperate").value(operate));
    }

    private void dropOperatorGrants() {
        jdbc.update("DELETE FROM permissions WHERE user_id = (SELECT id FROM users WHERE username = 'operator')");
    }

    @Test
    void rowFlagPerGrantLevel() throws Exception {
        String id = create();
        expectRow("admin", true);
        expectRow("operator", true);
        expectRow("viewer", false);
        dropOperatorGrants();
        grantDeployment("operator", UUID.fromString(id), "VIEW");
        expectRow("operator", false);
    }

    @Test
    void hostFlagAdminAndOperateGrant() throws Exception {
        expectHost("admin", true);
        expectHost("operator", true);
    }

    @Test
    void hostFlagViewOnlyGrantIsFalseEvenWithoutDeployments() throws Exception {
        jdbc.update("UPDATE permissions SET level = 'VIEW' WHERE user_id = (SELECT id FROM users WHERE username = 'operator')");
        expectHost("operator", false);
    }

    @Test
    void hostFlagDeploymentOnlyGrantIsFalse() throws Exception {
        String id = create();
        dropOperatorGrants();
        grantDeployment("operator", UUID.fromString(id), "OPERATE");
        expectHost("operator", false);
    }

    @Test
    void hostFlagViewerIsFalse() throws Exception {
        expectHost("viewer", false);
    }
}
