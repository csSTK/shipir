package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Needs a Docker daemon: the LOCAL environment is queried for real. */
class HostOverviewApiTest extends HostApiTestBase {

    @Test
    void localHostIsUpWithVersionResourcesAndCountsForViewer() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Local\",\"connectionType\":\"LOCAL\"}"))
                .andExpect(status().isCreated());

        Login viewer = login("viewer");
        mvc.perform(get("/api/hosts/overview").session(viewer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].name").value("Local"))
                .andExpect(jsonPath("$[0].connectionType").value("LOCAL"))
                .andExpect(jsonPath("$[0].endpoint").value(org.hamcrest.Matchers.startsWith("unix:///")))
                .andExpect(jsonPath("$[0].endpoint").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("unix://unix://"))))
                .andExpect(jsonPath("$[0].status").value("UP"))
                .andExpect(jsonPath("$[0].error").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].dockerVersion").isNotEmpty())
                .andExpect(jsonPath("$[0].cpus").isNumber())
                .andExpect(jsonPath("$[0].memoryBytes").isNumber())
                .andExpect(jsonPath("$[0].containers.running").isNumber())
                .andExpect(jsonPath("$[0].containers.stopped").isNumber())
                .andExpect(jsonPath("$[0].containers.healthy").isNumber())
                .andExpect(jsonPath("$[0].containers.unhealthy").isNumber())
                .andExpect(jsonPath("$[0].images").isNumber())
                .andExpect(jsonPath("$[0].volumes").isNumber());
    }

    @Test
    void emptyHostListReturnsEmptyArray() throws Exception {
        Login viewer = login("viewer");
        mvc.perform(get("/api/hosts/overview").session(viewer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/hosts/overview")).andExpect(status().isUnauthorized());
    }
}
