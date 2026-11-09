package dev.bethge.shipir.compose;

import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.compose.validation.ComposeSecurityPolicy;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Decision shipr-kqbc.53 (user, 2026-11-09): accept and document. A compose file whose {@code env_file}/{@code include}/
 * {@code extends} targets use variables ({@code $}), {@code ~} or remote sources is rejected by {@code ComposeFilePolicy}
 * even in the lenient ADMIN scan. {@code DeploymentService.referencesUnhashedPaths} reports such a reference as "not
 * covered by the graph hash", so {@code DeploymentRunService.start} stores no {@code graph_hash} for that ADMIN deploy.
 * Strict policy stays; this test pins the gate that decides it.
 *
 * <p>This is a documentation-pinning test: the behaviour already exists, so it passes on the first run (no red phase).
 * The control case shows the same gate lets an ordinary local {@code env_file} keep its hash.
 */
class GraphHashVariableReferencesTest {

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
        Files.createDirectories(dir.resolve("data"));
        when(execution.execute(any(), any(), eq(ComposeCommand.CONFIG_JSON), any(), any(), any(), any(), any()))
                .thenReturn(new ComposeProcessResult(0, false, List.of("{\"name\":\"web\",\"services\":{}}")));
    }

    private List<String> referencesThatBlockTheHash(String composeYaml) throws IOException {
        Files.writeString(dir.resolve("compose.yaml"), composeYaml);
        return policy.unhashedPathReferences(host, "web", endpoint, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "services:\n  x:\n    image: a\n    env_file: ${HOME}/x.env\n",
            "services:\n  x:\n    image: a\n    env_file: ./$DIR/x.env\n",
            "services:\n  x:\n    image: a\n    env_file: ~/x.env\n",
            "include:\n  - https://example.com/x.yaml\nservices: {}\n",
            "include:\n  - github.com/example/repo/x.yaml\nservices: {}\n",
            "services:\n  x:\n    extends:\n      file: oci://example.com/x.yaml\n      service: y\n"})
    void variableTildeAndRemoteReferencesBlockTheGraphHash(String composeYaml) throws IOException {
        assertThat(referencesThatBlockTheHash(composeYaml)).isNotEmpty();
    }

    @Test
    void ordinaryLocalEnvFileDoesNotBlockTheGraphHash() throws IOException {
        assertThat(referencesThatBlockTheHash("services:\n  x:\n    image: a\n    env_file: data/a.env\n")).isEmpty();
    }
}
