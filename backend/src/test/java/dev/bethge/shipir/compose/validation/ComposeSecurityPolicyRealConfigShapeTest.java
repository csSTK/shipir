package dev.bethge.shipir.compose.validation;

import dev.bethge.shipir.compose.DeploymentProperties;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeOptions;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.compose.execution.ComposeProperties;
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

/**
 * The API tests use a fake compose that passes YAML through, so short-form volumes and relative paths would never be
 * normalized. This runs the real {@code docker compose config --format json} (skipped without a docker compose CLI)
 * and feeds its output to the policy: short-form binds must show up as long-form binds and be judged by their resolved source.
 * Pins existing behaviour (no production change), hence no red phase.
 */
class ComposeSecurityPolicyRealConfigShapeTest {

    @TempDir
    Path tmp;

    @Test
    void shortFormAndRelativeVolumesAreNormalizedAndJudgedByTheirResolvedSource() throws IOException, InterruptedException {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon available");
        Process p = new ProcessBuilder("docker", "compose", "version").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        Assumptions.assumeTrue(p.waitFor() == 0, "docker compose CLI not available");

        var files = new ComposeFileRepository(new DeploymentProperties(tmp.resolve("deployments")));
        var execution = new ComposeExecutionService(files, new ComposeProperties(Duration.ofMinutes(1), List.of()), List::of);
        UUID hostId = UUID.randomUUID();
        String name = "shipir-shape-" + UUID.randomUUID().toString().substring(0, 8);
        files.write(hostId, name, """
                services:
                  a:
                    image: busybox:1.36
                    volumes:
                      - /:/hostroot
                      - ./data:/d
                      - /var/run/docker.sock:/s
                """, null);
        var endpoint = new DockerEndpoint.Local("unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath());

        List<String> out = new ArrayList<>();
        ComposeProcessResult r = execution.execute(hostId, name, ComposeCommand.CONFIG_JSON, endpoint, out::add, null, null,
                ComposeOptions.ofProfiles(List.of(ComposeOptions.ALL_PROFILES)));
        assertThat(r.exitCode()).as(String.join("\n", out)).isZero();

        var config = ComposeSecurityPolicy.parse(out);
        var volumes = config.path("services").path("a").path("volumes");
        assertThat(volumes).hasSize(3);
        assertThat(volumes).allSatisfy(v -> assertThat(v.path("type").asString()).isEqualTo("bind"));

        Path deploymentDir = files.deploymentDir(hostId, name);
        assertThat(ComposeSecurityPolicy.violationsOf(config, deploymentDir))
                .containsExactlyInAnyOrder("a: bind mount /", "a: docker.sock mount");
    }
}
