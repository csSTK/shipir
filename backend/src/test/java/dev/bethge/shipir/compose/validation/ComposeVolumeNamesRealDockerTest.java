package dev.bethge.shipir.compose.validation;

import dev.bethge.shipir.compose.DeploymentProperties;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeOptions;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.compose.execution.ComposeProperties;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.VolumeDto;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real {@code docker compose config} / real volumes (skipped without Docker): resolved volume names and the ownership check (shipr-kqbc.36). */
class ComposeVolumeNamesRealDockerTest {

    @TempDir
    Path tmp;

    @Test
    void resolvesVolumeNamesAsComposeDoesAndDeniesAttachingToAnotherProjectsVolume() throws IOException, InterruptedException {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon available");
        Process p = new ProcessBuilder("docker", "compose", "version").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        Assumptions.assumeTrue(p.waitFor() == 0, "docker compose CLI not available");

        var files = new ComposeFileRepository(new DeploymentProperties(tmp.resolve("deployments")));
        var execution = new ComposeExecutionService(files, new ComposeProperties(Duration.ofMinutes(1), List.of()), List::of);
        UUID hostId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "shipir-vol-" + suffix;
        String other = "shipir-other-" + suffix;
        files.write(hostId, name, """
                services:
                  a:
                    image: busybox:1.36
                    volumes: [plain:/p, named:/n, ext:/e, ext2:/e2]
                volumes:
                  plain: {}
                  named:
                    name: %s
                  ext:
                    external: true
                  ext2:
                    external: true
                    name: foreign
                """.formatted(other), null);
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        var endpoint = new DockerEndpoint.Local(socket);

        List<String> out = new ArrayList<>();
        ComposeProcessResult r = execution.execute(hostId, name, ComposeCommand.CONFIG_JSON, endpoint, out::add, null, null,
                ComposeOptions.ofProfiles(List.of(ComposeOptions.ALL_PROFILES)));
        assertThat(r.exitCode()).as(String.join("\n", out)).isZero();
        var names = ComposeSecurityPolicy.namedVolumes(ComposeSecurityPolicy.parse(out));
        assertThat(names.get("plain")).isEqualTo(new ComposeSecurityPolicy.NamedVolume(name + "_plain", false));
        assertThat(names.get("named")).isEqualTo(new ComposeSecurityPolicy.NamedVolume(other, false));
        assertThat(names.get("ext").external()).isTrue();
        assertThat(names.get("ext").name()).isEqualTo("ext");
        assertThat(names.get("ext2")).isEqualTo(new ComposeSecurityPolicy.NamedVolume("foreign", true));

        VolumeService volumes = mock(VolumeService.class);
        when(volumes.list(hostId, endpoint)).thenReturn(List.of(
                new VolumeDto(other, "local", "/x", null, false, "victim"),
                new VolumeDto(name + "_plain", "local", "/x", null, false, name),
                new VolumeDto("ext", "local", "/x", null, false, name),
                new VolumeDto("foreign", "local", "/x", null, false, "victim")));
        List<String> violations = new ComposeSecurityPolicy(execution, files, volumes).violations(hostId, name, endpoint, null);
        assertThat(violations).containsExactlyInAnyOrder(
                "volume named: volume " + other + " belongs to another deployment or is not managed by Compose",
                "volume ext2: external volume foreign does not belong to this deployment");
    }
}
