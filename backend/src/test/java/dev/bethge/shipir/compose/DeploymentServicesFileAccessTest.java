package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET services} runs compose on the stored file, so non-ADMIN callers get the file-access check first. */
class DeploymentServicesFileAccessTest extends DeploymentTestBase {

    private static final String OUTSIDE_ENV_FILE =
            "services:\n  web:\n    image: nginx\n    env_file: /proc/self/environ\n";

    private String adminCreates(String yaml) throws Exception {
        String body = mvc.perform(login("admin").write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("leaky", yaml, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void operatorIsDeniedAndComposeNeverRunsForFileOutsideTheDeploymentDir() throws Exception {
        String id = adminCreates(OUTSIDE_ENV_FILE);
        Mockito.clearInvocations(execution);

        mvc.perform(get(url() + "/" + id + "/services").session(login("operator").session()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));

        Mockito.verify(execution, never()).execute(any(), any(), eq(dev.bethge.shipir.compose.execution.ComposeCommand.CONFIG_SERVICES),
                any(), any(), any(), any(), any());
        Mockito.verify(execution, never()).execute(any(), any(), eq(dev.bethge.shipir.compose.execution.ComposeCommand.CONFIG_PROFILES),
                any(), any(), any(), any(), any());
    }

    @Test
    void adminStillListsServicesOfSuchAFile() throws Exception {
        String id = adminCreates(OUTSIDE_ENV_FILE);

        mvc.perform(get(url() + "/" + id + "/services").session(login("admin").session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.services[0]").value("web"));
    }

    @Test
    void operatorStillListsServicesOfAFileInsideTheDeploymentDir() throws Exception {
        String id = adminCreates("services:\n  web:\n    image: nginx\n    env_file: [app.env]\n");

        mvc.perform(get(url() + "/" + id + "/services").session(login("operator").session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.services[0]").value("web"));
    }
}
