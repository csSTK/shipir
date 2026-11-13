package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The detail response tells the UI whether the caller may OPERATE (shipr-0qbh.95), per grant level. */
class DeploymentCanOperateTest extends DeploymentTestBase {

    private String create() throws Exception {
        String body = mvc.perform(login("admin").write(post(url()).contentType(APPLICATION_JSON)
                        .content(createJson("web", YAML, null))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void expectCanOperate(String user, String id, boolean expected) throws Exception {
        mvc.perform(get(url() + "/" + id).session(login(user).session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canOperate").value(expected));
    }

    @Test
    void adminCanOperate() throws Exception {
        expectCanOperate("admin", create(), true);
    }

    @Test
    void operatorWithOperateHostGrantCanOperate() throws Exception {
        expectCanOperate("operator", create(), true);
    }

    @Test
    void operatorWithViewOnlyHostGrantCannotOperate() throws Exception {
        String id = create();
        jdbc.update("UPDATE permissions SET level = 'VIEW' WHERE user_id = (SELECT id FROM users WHERE username = 'operator')");
        expectCanOperate("operator", id, false);
    }

    @Test
    void operatorWithViewOnlyDeploymentGrantCannotOperate() throws Exception {
        String id = create();
        jdbc.update("DELETE FROM permissions WHERE user_id = (SELECT id FROM users WHERE username = 'operator')");
        grantDeployment("operator", UUID.fromString(id), "VIEW");
        expectCanOperate("operator", id, false);
    }

    @Test
    void operatorWithOperateDeploymentGrantCanOperate() throws Exception {
        String id = create();
        jdbc.update("DELETE FROM permissions WHERE user_id = (SELECT id FROM users WHERE username = 'operator')");
        grantDeployment("operator", UUID.fromString(id), "OPERATE");
        expectCanOperate("operator", id, true);
    }

    @Test
    void viewerCannotOperate() throws Exception {
        expectCanOperate("viewer", create(), false);
    }
}
