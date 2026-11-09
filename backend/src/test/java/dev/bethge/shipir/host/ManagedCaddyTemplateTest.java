package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The managed Caddy must start before the first sync has written its Caddyfile. */
class ManagedCaddyTemplateTest {

    @SuppressWarnings("unchecked")
    private static List<String> command() throws Exception {
        try (InputStream in = ManagedCaddyTemplateTest.class.getResourceAsStream("/templates/proxy/managed-caddy.yaml")) {
            Map<String, Object> doc = new Yaml().load(in);
            Map<String, Object> services = (Map<String, Object>) doc.get("services");
            Map<String, Object> caddy = (Map<String, Object>) services.get("caddy");
            return (List<String>) caddy.get("command");
        }
    }

    @Test
    void createsAMinimalCaddyfileOnlyWhenNoneExistsThenRunsCaddyWithIt() throws Exception {
        List<String> command = command();

        assertThat(command.subList(0, 2)).containsExactly("sh", "-c");
        String script = command.get(2);
        assertThat(script).contains("[ -f /etc/shipir-proxy/Caddyfile ] ||")
                .contains("> /etc/shipir-proxy/Caddyfile")
                .contains("exec caddy run --config /etc/shipir-proxy/Caddyfile --adapter caddyfile");
        assertThat(script.indexOf("[ -f")).isLessThan(script.indexOf("exec caddy"));
        // compose would interpolate a dollar sign
        assertThat(script).doesNotContain("$");
    }
}
