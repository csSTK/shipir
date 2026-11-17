package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.common.security.AiInvocationContext;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The async *_RESULT audit entry carries via=AI when the run was accepted inside an AI call (the worker thread does not inherit it). */
class DeploymentAiAuditTest extends DeploymentTestBase {

    @MockitoBean
    ComposeOutputStreamService streams;

    @Autowired
    DeploymentRunService runs;

    private String create(Login l) throws Exception {
        when(streams.start(any())).thenReturn((Consumer<String>) line -> { });
        String body = mvc.perform(l.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("web", YAML, "[]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String resultDetails(String action) {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForList("SELECT 1 FROM audit_log WHERE action = ?", action)).isNotEmpty());
        return jdbc.queryForObject("SELECT cast(details as text) FROM audit_log WHERE action = ?", String.class, action);
    }

    @Test
    void deployFromChatMarksResultEntryAsAi() throws Exception {
        String id = create(login("admin"));
        UUID session = UUID.randomUUID();
        dev.bethge.shipir.common.security.SystemScope.call(() -> AiInvocationContext.callAs(session, () -> runs.deploy(hostId, UUID.fromString(id), "admin")));

        String details = resultDetails("DEPLOYMENT_DEPLOY_RESULT");
        assertThat((String) JsonPath.read(details, "$.via")).isEqualTo("AI");
        assertThat((String) JsonPath.read(details, "$.chatSessionId")).isEqualTo(session.toString());
        assertThat((String) JsonPath.read(details, "$.runId")).isNotBlank();
    }

    @Test
    void rollbackFromChatMarksResultEntryAsAi() throws Exception {
        String id = create(login("admin"));
        UUID session = UUID.randomUUID();
        UUID versionId = jdbc.queryForObject("SELECT id FROM deployment_versions LIMIT 1", UUID.class);
        dev.bethge.shipir.common.security.SystemScope.call(() -> AiInvocationContext.callAs(session, () -> runs.rollback(hostId, UUID.fromString(id), versionId, "admin")));

        String details = resultDetails("DEPLOYMENT_ROLLBACK_RESULT");
        assertThat((String) JsonPath.read(details, "$.via")).isEqualTo("AI");
        assertThat((String) JsonPath.read(details, "$.chatSessionId")).isEqualTo(session.toString());
    }

    @Test
    void normalDeployHasNoMarker() throws Exception {
        String id = create(login("admin"));
        dev.bethge.shipir.common.security.SystemScope.run(() -> runs.deploy(hostId, UUID.fromString(id), "admin"));

        String details = resultDetails("DEPLOYMENT_DEPLOY_RESULT");
        assertThat(details).doesNotContain("via").doesNotContain("chatSessionId");
        assertThat(List.of(details)).isNotEmpty();
    }
}
