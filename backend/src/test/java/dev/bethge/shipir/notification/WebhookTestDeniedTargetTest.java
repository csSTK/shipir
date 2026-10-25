package dev.bethge.shipir.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Default policy (private ranges denied): the test endpoint answers a denied target exactly like any failing one. */
@TestPropertySource(properties = "shipir.egress.allow-private-ranges=false")
class WebhookTestDeniedTargetTest extends WebhookTestBase {

    @Autowired
    ObjectMapper mapper;

    @Test
    void deniedLoopbackTargetYieldsPlainFailureAndNoRequest() throws Exception {
        Stub stub = stub(200, 0);
        Login l = login("admin");
        String created = mvc.perform(l.write(post("/api/notifications/webhooks")).contentType(APPLICATION_JSON)
                        .content(createJson("Internal", stub.url("/"), "[\"HOST_UP\"]", true)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(created).get("id").asText();
        mvc.perform(l.write(post("/api/notifications/webhooks/" + id + "/test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(stub.bodies).isEmpty();
        assertThat(jdbc.queryForObject("SELECT last_delivery_status FROM notification_webhooks WHERE id = ?::uuid",
                String.class, id)).isEqualTo("FAILED");
    }
}
