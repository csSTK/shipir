package dev.bethge.shipir.compose.execution;

import dev.bethge.shipir.compose.DeploymentProperties;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.compose.validation.ComposeSecurityPolicy;
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

/**
 * Real {@code docker compose up} (shipr-kqbc.28): documents what happens to a user-set
 * {@code com.docker.compose.project} label. {@code ManagedDeploymentLookup} attributes a container to a deployment by this
 * label, so a forged value must never reach another deployment's grant.
 */
class ComposeProjectLabelRealDockerTest {

    @TempDir
    Path tmp;

    @Test
    void userSetProjectLabelDoesNotSurviveOnTheCreatedContainer() throws IOException, InterruptedException {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon available");
        Process p = new ProcessBuilder("docker", "compose", "version").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        Assumptions.assumeTrue(p.waitFor() == 0, "docker compose CLI not available");

        var files = new ComposeFileRepository(new DeploymentProperties(tmp.resolve("deployments")));
        var service = new ComposeExecutionService(files, new ComposeProperties(Duration.ofMinutes(3), List.of()), List::of);
        UUID hostId = UUID.randomUUID();
        String name = "shipir-lbl-" + UUID.randomUUID().toString().substring(0, 8);
        String forged = "forged-victim-" + UUID.randomUUID().toString().substring(0, 8);
        files.write(hostId, name, """
                services:
                  sleeper:
                    image: busybox:1.36
                    command: sleep 300
                    labels:
                      com.docker.compose.project: %s
                      com.docker.compose.service: forged-service
                """.formatted(forged), null);
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        var endpoint = new DockerEndpoint.Local(socket);
        try {
            List<String> up = new ArrayList<>();
            ComposeProcessResult r = service.execute(hostId, name, ComposeCommand.UP, endpoint, up::add);
            assertThat(r.exitCode()).as(String.join("\n", up)).isZero();

            String forgedHits = docker(socket, "ps", "-a", "-q", "--filter", "label=com.docker.compose.project=" + forged);
            String ownLabels = docker(socket, "ps", "-a", "--filter", "label=com.docker.compose.project=" + name,
                    "--format", "{{.Label \"com.docker.compose.project\"}}|{{.Label \"com.docker.compose.service\"}}");
            assertThat(forgedHits).as("container carrying the forged project label").isBlank();
            assertThat(ownLabels.trim()).isEqualTo(name + "|sleeper");
        } finally {
            service.execute(hostId, name, ComposeCommand.DOWN, endpoint, l -> { });
        }
    }

    @Test
    void policyReportsComposeLabelsProvidedThroughLabelFile() throws IOException, InterruptedException {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon available");
        Process p = new ProcessBuilder("docker", "compose", "version").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        Assumptions.assumeTrue(p.waitFor() == 0, "docker compose CLI not available");

        var files = new ComposeFileRepository(new DeploymentProperties(tmp.resolve("deployments-lf")));
        var service = new ComposeExecutionService(files, new ComposeProperties(Duration.ofMinutes(1), List.of()), List::of);
        UUID hostId = UUID.randomUUID();
        String name = "shipir-lf-" + UUID.randomUUID().toString().substring(0, 8);
        files.write(hostId, name, """
                services:
                  a:
                    image: busybox:1.36
                    label_file: ./labels.env
                """, null);
        java.nio.file.Files.writeString(files.deploymentDir(hostId, name).resolve("labels.env"),
                "com.docker.compose.project=victim\n");
        var endpoint = new DockerEndpoint.Local("unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath());

        List<String> violations = new ComposeSecurityPolicy(service, files, org.mockito.Mockito.mock(dev.bethge.shipir.docker.client.VolumeService.class)).violations(hostId, name, endpoint, null);

        assertThat(violations).contains("a: labels com.docker.compose.project");
    }

    private static String docker(String socket, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("docker", "-H", socket));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }
}
