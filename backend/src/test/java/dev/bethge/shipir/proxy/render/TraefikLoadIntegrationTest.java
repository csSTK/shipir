package dev.bethge.shipir.proxy.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in check that Traefik loads the generated dynamic config: starts the pinned image (the one in
 * {@code managed-traefik.yaml}) on a directory with each golden {@code managed.yml} plus an empty {@code custom.yml}
 * and fails on configuration errors in its log. Skipped without Docker or the image locally (nothing is pulled).
 * ACME errors are expected (the names do not resolve to this machine) and ignored; the custom-certificate golden is
 * left out because it needs certificate files.
 */
class TraefikLoadIntegrationTest {

    private static final String IMAGE = "traefik:v3.5.3";
    private static final List<String> ERROR_MARKERS = List.of("error while building configuration", "cannot ",
            "unmarshal", "unknown ", "invalid", "does not exist", "doesn't exist", "yaml:");

    @Test
    void traefikLoadsEveryGoldenManagedFile() throws Exception {
        assumeTrue(imageAvailable(), IMAGE + " not available locally");
        for (String name : List.of("minimal", "advanced", "internal", "wildcard-dns", "ordering")) {
            Path dir = Files.createTempDirectory("traefik-load-");
            String container = "shipir-traefik-load-" + UUID.randomUUID().toString().substring(0, 8);
            try {
                Files.writeString(dir.resolve("managed.yml"), RenderFixtures.golden("traefik/" + name + ".yml"));
                Files.writeString(dir.resolve("custom.yml"), "");
                dir.toFile().setReadable(true, false);
                dir.toFile().setExecutable(true, false);
                for (String f : List.of("managed.yml", "custom.yml")) {
                    dir.resolve(f).toFile().setReadable(true, false);
                }
                run("docker", "run", "-d", "--name", container, "-v", dir + ":/etc/shipir-proxy:ro", IMAGE,
                        "--log.level=DEBUG",
                        "--entrypoints.web.address=:80", "--entrypoints.websecure.address=:443",
                        "--entrypoints.custom.address=:8443",
                        "--providers.file.directory=/etc/shipir-proxy", "--providers.file.watch=true",
                        "--certificatesresolvers.letsencrypt-http.acme.email=ops@example.com",
                        "--certificatesresolvers.letsencrypt-http.acme.storage=/tmp/acme-http.json",
                        "--certificatesresolvers.letsencrypt-http.acme.tlschallenge=true",
                        "--certificatesresolvers.letsencrypt-dns.acme.email=ops@example.com",
                        "--certificatesresolvers.letsencrypt-dns.acme.storage=/tmp/acme-dns.json",
                        "--certificatesresolvers.letsencrypt-dns.acme.dnschallenge=true",
                        "--certificatesresolvers.letsencrypt-dns.acme.dnschallenge.provider=manual");
                Thread.sleep(6000);
                String logs = run("docker", "logs", container);
                List<String> bad = logs.lines()
                        .filter(l -> l.toLowerCase().contains("level=error") || l.contains(" ERR "))
                        .filter(l -> !l.toLowerCase().contains("acme"))
                        .filter(l -> ERROR_MARKERS.stream().anyMatch(m -> l.toLowerCase().contains(m)))
                        .toList();
                assertThat(bad).as(name + " log").isEmpty();
            } finally {
                run("docker", "rm", "-f", container);
                try (var files = Files.list(dir)) {
                    for (Path f : files.toList()) {
                        Files.deleteIfExists(f);
                    }
                }
                Files.deleteIfExists(dir);
            }
        }
    }

    private static String run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return out;
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
