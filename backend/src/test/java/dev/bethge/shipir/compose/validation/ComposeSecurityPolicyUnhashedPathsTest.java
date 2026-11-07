package dev.bethge.shipir.compose.validation;

import dev.bethge.shipir.compose.DeploymentProperties;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionException;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.VolumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** References into paths the graph hash skips (shipr-kqbc.42): config values and raw include/extends/env_file targets. */
class ComposeSecurityPolicyUnhashedPathsTest {

    @TempDir
    Path tmp;

    private final UUID host = UUID.randomUUID();
    private final DockerEndpoint endpoint = new DockerEndpoint.Local("unix:///var/run/docker.sock");
    private ComposeFileRepository files;
    private ComposeExecutionService execution;
    private ComposeSecurityPolicy policy;
    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        files = new ComposeFileRepository(new DeploymentProperties(tmp));
        execution = mock(ComposeExecutionService.class);
        policy = new ComposeSecurityPolicy(execution, files, mock(VolumeService.class));
        files.write(host, "web", "services: {}\n", null);
        dir = files.deploymentDir(host, "web");
        Files.createDirectories(dir.resolve(".shipir-backups"));
        Files.createDirectories(dir.resolve("repo/.git"));
        Files.createDirectories(dir.resolve("data"));
    }

    private void config(String servicesJson) {
        when(execution.execute(any(), any(), eq(ComposeCommand.CONFIG_JSON), any(), any(), any(), any(), any()))
                .thenReturn(new ComposeProcessResult(0, false, List.of("{\"name\":\"web\",\"services\":" + servicesJson + "}")));
    }

    private List<String> run() {
        return policy.unhashedPathReferences(host, "web", endpoint, null);
    }

    private String bind(String source) {
        return "{\"x\":{\"image\":\"a\",\"volumes\":[{\"type\":\"bind\",\"source\":\"%s\",\"target\":\"/t\"}]}}".formatted(source);
    }

    @ParameterizedTest
    @ValueSource(strings = {".shipir-backups", ".shipir-backups/a/b", "repo/.git", "repo/.git/config", "data/../.shipir-backups/x",
            "repo/.git/../.git/HEAD"})
    void findsBindSourcesIntoUnhashedPaths(String relative) {
        config(bind(dir + "/" + relative));
        assertThat(run()).hasSize(1);
    }

    @Test
    void findsReferencesThroughASymlinkInsideTheDirectory() throws IOException {
        Files.createSymbolicLink(dir.resolve("data/link"), dir.resolve(".shipir-backups"));
        config(bind(dir + "/data/link/x"));
        assertThat(run()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"data", "repo", "repo/src", "repo/.github", "repo/.gitmodules", ".shipir-backups-old", "x.shipir-backups"})
    void ignoresHashedAndLookalikePaths(String relative) {
        config(bind(dir + "/" + relative));
        assertThat(run()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"env_file: .shipir-backups/a.env", "env_file: [repo/.git/config]", "env_file: ./data/../.shipir-backups/x",
            "env_file: [{path: repo/.git/x}]"})
    void findsEnvFileTargetsIntoUnhashedPaths(String envFile) throws IOException {
        Files.writeString(dir.resolve("compose.yaml"), "services:\n  x:\n    image: a\n    " + envFile + "\n");
        config("{}");
        assertThat(run()).isNotEmpty();
    }

    @Test
    void findsIncludeAndExtendsTargetsIntoUnhashedPaths() throws IOException {
        Files.writeString(dir.resolve("compose.yaml"), "include:\n  - .shipir-backups/other.yaml\nservices:\n  x:\n    extends:\n      file: repo/.git/base.yaml\n      service: y\n");
        config("{}");
        assertThat(run()).hasSize(2);
    }

    @Test
    void allowsOrdinaryEnvFileAndConfigWithoutReferences() throws IOException {
        Files.writeString(dir.resolve("compose.yaml"), "services:\n  x:\n    image: a\n    env_file: data/a.env\n");
        config(bind(dir + "/data"));
        assertThat(run()).isEmpty();
    }

    @Test
    void failsClosedWhenComposeCannotRun() {
        when(execution.execute(any(), any(), eq(ComposeCommand.CONFIG_JSON), any(), any(), any(), any(), any()))
                .thenReturn(new ComposeProcessResult(1, false, List.of("boom")));
        assertThatThrownBy(this::run).isInstanceOf(ComposeExecutionException.class);
    }
}
