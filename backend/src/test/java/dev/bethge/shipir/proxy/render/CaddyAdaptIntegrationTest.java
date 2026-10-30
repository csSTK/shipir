package dev.bethge.shipir.proxy.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs the pinned Caddy image's {@code caddy adapt} over every golden Caddyfile. Skipped without Docker or the image. */
class CaddyAdaptIntegrationTest {

    private static final String IMAGE = "caddy:2.10.2-alpine";

    @Test
    void caddyAcceptsEveryGoldenCaddyfile() throws Exception {
        assumeTrue(imageAvailable(), IMAGE + " not available locally");
        for (String name : List.of("minimal", "no-email", "advanced", "internal", "custom", "ordering", "hostile")) {
            Path dir = Files.createTempDirectory("caddy-adapt-");
            try {
                Files.writeString(dir.resolve("Caddyfile"), RenderFixtures.golden("caddy/" + name + ".caddy"));
                Files.writeString(dir.resolve("custom.caddy"), "");
                Process p = new ProcessBuilder("docker", "run", "--rm", "-v", dir + ":/c:ro", IMAGE, "caddy", "adapt",
                        "--config", "/c/Caddyfile").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes());
                assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
                assertThat(p.exitValue()).as(name + ": " + out).isZero();
            } finally {
                try (var files = Files.list(dir)) {
                    for (Path f : files.toList()) {
                        Files.deleteIfExists(f);
                    }
                }
                Files.deleteIfExists(dir);
            }
        }
    }

    private static boolean imageAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", IMAGE).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
