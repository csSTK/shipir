package dev.bethge.shipir.compose.validation;

import dev.bethge.shipir.compose.DeploymentProperties;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeProperties;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.DockerEndpoint;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real {@code docker compose config --quiet} (needs only the CLI, not a daemon). */
class ComposeValidationServiceTest {

    @TempDir
    Path tmp;

    private final UUID hostId = UUID.randomUUID();
    private ComposeFileRepository files;
    private ComposeValidationService validation;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("docker", "compose", "version").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        Assumptions.assumeTrue(p.waitFor() == 0, "docker compose CLI not available");
        files = new ComposeFileRepository(new DeploymentProperties(tmp.resolve("deployments")));
        validation = new ComposeValidationService(
                new ComposeExecutionService(files, new ComposeProperties(Duration.ofSeconds(30), java.util.List.of()), List::of));
    }

    private ValidationResult validate() {
        return validation.validate(hostId, "web", new DockerEndpoint.Local("unix:///var/run/docker.sock"));
    }

    @Test
    void validYamlIsValid() {
        files.write(hostId, "web", "services:\n  app:\n    image: busybox\n", null);
        ValidationResult result = validate();
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void invalidYamlReturnsErrors() {
        files.write(hostId, "web", "services:\n  app:\n    image: [unclosed\n", null);
        ValidationResult result = validate();
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void schemaViolationReturnsErrors() {
        files.write(hostId, "web", "services:\n  app:\n    imagee: busybox\n", null);
        ValidationResult result = validate();
        assertThat(result.valid()).isFalse();
        assertThat(String.join("\n", result.errors())).contains("imagee");
    }

    /**
     * Profiled services are only checked with {@code --profile *}: a broken profiled service passes a plain
     * {@code config --quiet} (verified against Compose v5.1.3) but must fail validation.
     */
    @Test
    void invalidProfiledServiceFailsValidation() {
        files.write(hostId, "web", """
                services:
                  app:
                    image: busybox
                  debug-tools:
                    image: busybox
                    profiles: [debug]
                    depends_on: [missing]
                """, null);
        ValidationResult result = validate();
        assertThat(result.valid()).isFalse();
        assertThat(String.join("\n", result.errors())).contains("missing");
    }

    @Test
    void timeoutResultIsInvalid() {
        files.write(hostId, "web", "services: {}\n", null);
        var v = new ComposeValidationService(
                new ComposeExecutionService(files, new ComposeProperties(Duration.ofMillis(1), java.util.List.of()), List::of));
        ValidationResult r = v.validate(hostId, "web", new DockerEndpoint.Local("unix:///var/run/docker.sock"));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).isNotEmpty();
    }
}
