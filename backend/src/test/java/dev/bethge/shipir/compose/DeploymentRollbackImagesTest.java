package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@link DeploymentRunService#rollbackImages} must run against the deployment's own compose file, plus the override. */
class DeploymentRollbackImagesTest extends DeploymentTestBase {

    private static final String PIN = "nginx@sha256:" + "a".repeat(64);

    @Autowired
    DeploymentRunService runs;

    private UUID create(String name) throws Exception {
        Login op = login("operator");
        String body = mvc.perform(op.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson(name, YAML, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        commands.clear();
        composeFiles.clear();
        overrideYamls.clear();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    @Test
    void rollbackOfGitDeploymentUsesTheCheckoutComposeFileWithTheOverride() throws Exception {
        UUID id = create("gitd");
        java.nio.file.Files.createDirectories(files.repoDir(hostId, "gitd").resolve("deploy"));
        jdbc.update("UPDATE deployments SET source_type = 'GIT', git_repo_url = 'https://example.com/r.git', "
                + "git_ref = 'main', git_compose_path = 'deploy/compose.yaml' WHERE id = ?", id);

        boolean ok = runs.rollbackImages(id, Map.of("web", PIN));

        assertThat(ok).isTrue();
        assertThat(commands).containsExactly(ComposeCommand.UP);
        assertThat(composeFiles).singleElement().satisfies(f -> {
            assertThat(f).isNotNull();
            assertThat(f.toString()).endsWith("gitd/repo/deploy/compose.yaml".replace('/', java.io.File.separatorChar));
        });
        assertThat(overrideYamls).singleElement().satisfies(y -> assertThat(y).contains("\"web\":"));
    }

    @Test
    void rollbackOfFileDeploymentUsesTheDefaultComposeFileWithTheOverride() throws Exception {
        UUID id = create("filed");

        boolean ok = runs.rollbackImages(id, Map.of("web", PIN));

        assertThat(ok).isTrue();
        assertThat(composeFiles).singleElement().isNull();
        assertThat(overrideYamls).hasSize(1);
    }
}
