package dev.bethge.shipir.compose.generator;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProxyTemplateTest {

    private final ProxyTemplates templates = new ProxyTemplates();

    @Test
    void traefikTemplateMatchesTheLabelsTheGeneratorWrites() {
        String yaml = templates.yaml("traefik");
        assertThat(yaml)
                .containsPattern("image: traefik:v3\\.\\d+\\n")
                .contains("root-equivalent")
                .contains("--entrypoints.websecure.address=:443")
                .contains("--certificatesresolvers.letsencrypt.acme.tlschallenge=true")
                .contains("--providers.docker.exposedbydefault=false")
                .contains("--providers.docker.network=proxy")
                .contains("/var/run/docker.sock:/var/run/docker.sock:ro")
                .contains("name: proxy");
    }

    @Test
    void emailComesFromEnvAndIsRequired() {
        assertThat(templates.yaml("traefik")).contains("${ACME_EMAIL:?");
    }

    @Test
    void unknownProxyIsRejected() {
        assertThatThrownBy(() -> templates.yaml("caddy")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> templates.yaml("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void traefikTemplatePassesDockerComposeConfig() throws Exception {
        assumeTrue(composeAvailable(), "docker compose CLI not available");
        Path dir = Files.createTempDirectory("proxy-tpl");
        try {
            Files.writeString(dir.resolve("compose.yaml"), templates.yaml("traefik"));
            Files.writeString(dir.resolve(".env"), "ACME_EMAIL=ops@example.com\n");
            Process p = new ProcessBuilder("docker", "compose", "-p", "proxytest", "-f", "compose.yaml", "config", "-q")
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
