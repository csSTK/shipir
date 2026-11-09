package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SystemManagedGuard} protects the managed proxy network by name. The bundled proxy templates must declare that
 * same network, or the guard would protect a name the proxy never uses (and the template's real network would only be
 * covered by the template scan). host/ cannot import proxy/, so the chain is guard = template here and
 * template = {@code ProxySyncService.PROXY_NETWORK} in {@code ManagedProxyTemplatesTest}.
 */
class SystemManagedGuardProxyNetworkTest {

    @Test
    void everyBundledProxyTemplateDeclaresTheNetworkTheGuardProtects() throws IOException {
        for (String resource : new String[] {"templates/proxy/managed-traefik.yaml", "templates/proxy/managed-caddy.yaml"}) {
            String yaml = new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);

            assertThat(ManagedProxyTemplateResources.parse(yaml).networks())
                    .as(resource + " networks")
                    .contains(SystemManagedGuard.PROXY_NETWORK);
        }
    }
}
