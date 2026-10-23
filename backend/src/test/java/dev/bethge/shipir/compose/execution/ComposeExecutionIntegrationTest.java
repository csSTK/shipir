package dev.bethge.shipir.compose.execution;

import dev.bethge.shipir.compose.DeploymentProperties;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.DockerEndpoint;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real {@code docker compose up/down} against the local daemon. */
class ComposeExecutionIntegrationTest {

    @TempDir
    Path tmp;

    @Test
    void upAndDownOfTinyDeployment() throws IOException, InterruptedException {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon available");
        Process p = new ProcessBuilder("docker", "compose", "version").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        Assumptions.assumeTrue(p.waitFor() == 0, "docker compose CLI not available");

        var files = new ComposeFileRepository(new DeploymentProperties(tmp.resolve("deployments")));
        var service = new ComposeExecutionService(files, new ComposeProperties(Duration.ofMinutes(3)));
        UUID hostId = UUID.randomUUID();
        String name = "shipir-it-" + UUID.randomUUID().toString().substring(0, 8);
        files.write(hostId, name, "services:\n  sleeper:\n    image: busybox:1.36\n    command: sleep 300\n", null);
        var endpoint = new DockerEndpoint.Local("unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath());
        try {
            List<String> up = new ArrayList<>();
            ComposeProcessResult upResult = service.execute(hostId, name, ComposeCommand.UP, endpoint, up::add);
            assertThat(upResult.exitCode()).as(String.join("\n", up)).isZero();
            assertThat(up).isNotEmpty();
            assertThat(upResult.output()).isEqualTo(up);
        } finally {
            ComposeProcessResult down = service.execute(hostId, name, ComposeCommand.DOWN, endpoint, l -> { });
            assertThat(down.success()).isTrue();
        }
    }

    @Test
    void failingCommandMapsExitCode() {
        var files = new ComposeFileRepository(new DeploymentProperties(tmp.resolve("deployments2")));
        var service = new ComposeExecutionService(files, new ComposeProperties(Duration.ofSeconds(30)));
        UUID hostId = UUID.randomUUID();
        files.write(hostId, "bad", "services:\n  a:\n    imagee: x\n", null);
        ComposeProcessResult r = service.execute(hostId, "bad", ComposeCommand.CONFIG,
                new DockerEndpoint.Local("unix:///var/run/docker.sock"), l -> { });
        assertThat(r.exitCode()).isNotZero();
        assertThat(r.success()).isFalse();
    }

    @Test
    void executeProjectRemovesContainersAndNetworkOfAProjectWithoutFiles() throws IOException, InterruptedException {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon available");
        Process p = new ProcessBuilder("docker", "compose", "version").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        Assumptions.assumeTrue(p.waitFor() == 0, "docker compose CLI not available");

        var files = new ComposeFileRepository(new DeploymentProperties(tmp.resolve("deployments3")));
        var service = new ComposeExecutionService(files, new ComposeProperties(Duration.ofMinutes(3)));
        UUID hostId = UUID.randomUUID();
        String name = "shipir-it-" + UUID.randomUUID().toString().substring(0, 8);
        files.write(hostId, name, "services:\n  sleeper:\n    image: busybox:1.36\n    command: sleep 300\n", null);
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        var endpoint = new DockerEndpoint.Local(socket);
        try {
            assertThat(service.execute(hostId, name, ComposeCommand.UP, endpoint, l -> { }).success()).isTrue();
            assertThat(dockerLines(socket, "ps", "-aq", "--filter", "label=com.docker.compose.project=" + name)).isNotEmpty();
            assertThat(dockerLines(socket, "network", "ls", "-q", "--filter", "label=com.docker.compose.project=" + name))
                    .isNotEmpty();

            // the project is treated as unmanaged: no compose file involved
            ComposeProcessResult down = service.executeProject(name, ComposeCommand.DOWN, endpoint, l -> { });
            assertThat(down.success()).as(String.join("\n", down.output())).isTrue();

            assertThat(dockerLines(socket, "ps", "-aq", "--filter", "label=com.docker.compose.project=" + name)).isEmpty();
            assertThat(dockerLines(socket, "network", "ls", "-q", "--filter", "label=com.docker.compose.project=" + name))
                    .isEmpty();
        } finally {
            service.execute(hostId, name, ComposeCommand.DOWN, endpoint, l -> { });
        }
    }

    private static List<String> dockerLines(String socket, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("docker", "-H", socket));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out.lines().filter(l -> !l.isBlank()).toList();
    }
}
