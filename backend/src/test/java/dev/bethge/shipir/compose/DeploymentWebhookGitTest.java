package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.git.GitCheckout;
import dev.bethge.shipir.git.GitRepositoryService;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The webhook of a Git deployment pulls the repository and deploys the new commit (git redeploy), no compose pull. */
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class DeploymentWebhookGitTest extends DeploymentTestBase {

    @MockitoBean
    ComposeOutputStreamService streams;
    @MockitoBean
    GitRepositoryService git;

    @BeforeEach
    void stubGit() {
        when(streams.start(any())).thenReturn((Consumer<String>) line -> { });
        when(git.sync(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Path repoDir = inv.getArgument(0);
            Files.createDirectories(repoDir);
            Files.writeString(repoDir.resolve("compose.yaml"), YAML);
            return new GitCheckout("a".repeat(40), YAML);
        });
    }

    @Test
    void gitDeploymentIsPulledAndRedeployedAsSystem() throws Exception {
        Login op = login("operator");
        String created = mvc.perform(op.write(post(url())).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"gitdep\",\"source\":\"GIT\",\"repoUrl\":\"https://example.com/r.git\","
                                + "\"ref\":\"main\",\"composePath\":\"compose.yaml\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        String token = JsonPath.read(mvc.perform(op.write(put(url() + "/" + id + "/webhook")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
        commands.clear();

        mvc.perform(post("/api/webhooks/deploy/" + token).with(r -> {
            r.setRemoteAddr("10.77.1.1");
            return r;
        })).andExpect(status().isAccepted()).andExpect(jsonPath("$.runId").exists());

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'DEPLOYMENT_GIT_REDEPLOY_RESULT'",
                        Integer.class)).isEqualTo(1));
        verify(git, times(2)).sync(any(), any(), any(), any(), any());
        assertThat(commands).contains(ComposeCommand.UP).doesNotContain(ComposeCommand.PULL);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment_versions WHERE source = 'GIT' AND deployed_at IS NOT NULL",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT username FROM audit_log WHERE action = 'DEPLOYMENT_WEBHOOK_TRIGGER'",
                String.class)).isEqualTo("system");
    }
}
