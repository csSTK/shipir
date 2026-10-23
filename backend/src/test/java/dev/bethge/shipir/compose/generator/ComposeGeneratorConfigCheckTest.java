package dev.bethge.shipir.compose.generator;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration: the generated YAML is accepted by {@code docker compose config} (the same check
 * ComposeValidationService will run). Skipped when the docker CLI with the compose plugin is missing.
 */
class ComposeGeneratorConfigCheckTest {

    @Test
    void generatedYamlPassesDockerComposeConfig() throws Exception {
        assumeTrue(composeAvailable(), "docker compose CLI not available");
        String yaml = new ComposeGeneratorService().generate(ComposeGeneratorServiceTest.example());
        Path dir = Files.createTempDirectory("compose-gen");
        try {
            Files.writeString(dir.resolve("compose.yaml"), yaml);
            Files.writeString(dir.resolve(".env"), "DB_PASSWORD=secret\n");
            Process p = new ProcessBuilder("docker", "compose", "-p", "gentest", "-f", "compose.yaml", "config", "-q")
                    .directory(dir.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(p.exitValue()).as(out).isZero();
        } finally {
            Files.deleteIfExists(dir.resolve("compose.yaml"));
            Files.deleteIfExists(dir.resolve(".env"));
            Files.deleteIfExists(dir);
        }
    }

    private static boolean composeAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "compose", "version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
