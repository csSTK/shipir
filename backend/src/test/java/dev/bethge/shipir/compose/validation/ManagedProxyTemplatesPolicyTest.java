package dev.bethge.shipir.compose.validation;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.proxy.ManagedProxyTemplates;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The bundled managed proxy templates are deployed by an ADMIN (proxy setup requires ADMIN) and ADMIN is exempt from
 * {@link ComposeSecurityPolicy}. They are still run through the restricted (non-ADMIN) policy so a template never
 * silently depends on that exemption, e.g. a Docker socket mount or a host-path bind.
 */
class ManagedProxyTemplatesPolicyTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(ProxyType.class)
    void templateHasNoViolationsUnderTheRestrictedPolicy(ProxyType type) throws Exception {
        assumeTrue(composeAvailable(), "docker compose CLI not available");
        Path composeFile = dir.resolve("compose.yaml");
        Files.writeString(composeFile, new ManagedProxyTemplates().yaml(type));
        Files.writeString(dir.resolve(".env"), "ACME_EMAIL=ops@example.com\nDNS_PROVIDER=cloudflare\n");

        List<String> output = composeConfigJson();
        List<String> violations = new ArrayList<>(ComposeSecurityPolicy.violationsOf(ComposeSecurityPolicy.parse(output), dir));
        violations.addAll(ComposeFilePolicy.violations(composeFile, dir, true));

        assertThat(violations).as(type + " policy violations").isEmpty();
    }

    private List<String> composeConfigJson() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("docker", "compose", "-p", "managedproxypolicy", "-f", "compose.yaml",
                "config", "--format", "json").directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        return out.lines().toList();
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
