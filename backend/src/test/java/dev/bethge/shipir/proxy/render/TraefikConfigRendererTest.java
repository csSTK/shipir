package dev.bethge.shipir.proxy.render;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.proxy.BackendScheme;
import dev.bethge.shipir.proxy.CertMode;
import dev.bethge.shipir.proxy.ProxyRoute;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static dev.bethge.shipir.proxy.render.RenderFixtures.CERT_ID;
import static dev.bethge.shipir.proxy.render.RenderFixtures.HASH;
import static dev.bethge.shipir.proxy.render.RenderFixtures.certificate;
import static dev.bethge.shipir.proxy.render.RenderFixtures.full;
import static dev.bethge.shipir.proxy.render.RenderFixtures.golden;
import static dev.bethge.shipir.proxy.render.RenderFixtures.instance;
import static dev.bethge.shipir.proxy.render.RenderFixtures.route;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraefikConfigRendererTest {

    private final TraefikConfigRenderer renderer = new TraefikConfigRenderer();
    private final YAMLMapper yaml = YAMLMapper.builder().build();

    private String render(List<ProxyRoute> routes) {
        List<RenderedFile> files = renderer.render(instance(ProxyType.TRAEFIK, null), routes,
                Map.of(CERT_ID, certificate()));
        assertThat(files).extracting(RenderedFile::name).containsExactly("managed.yml");
        return files.get(0).content();
    }

    @Test
    void minimalRoute() {
        assertThat(render(List.of(route("app.example.com", "app", 8080, CertMode.ACME_HTTP))))
                .isEqualTo(golden("traefik/minimal.yml"));
    }

    @Test
    void allAdvancedOptions() {
        ProxyRoute r = full("api.example.com", List.of("www.example.com"), "api-1", 8443, BackendScheme.HTTPS, "/v1/",
                "custom", CertMode.ACME_HTTP, null, false, Map.of("alice", HASH),
                Map.of("X-Test", "a;b # c: 'd' %", "X-Frame-Options", "DENY"));
        assertThat(render(List.of(r))).isEqualTo(golden("traefik/advanced.yml"));
    }

    @Test
    void wildcardDnsRoute() {
        assertThat(render(List.of(route("*.example.com", "app", 80, CertMode.ACME_DNS))))
                .isEqualTo(golden("traefik/wildcard-dns.yml"));
    }

    @Test
    void wildcardWithApexAliasListsTheApexAsSan() throws Exception {
        ProxyRoute r = full("*.example.com", List.of("example.com"), "app", 80, BackendScheme.HTTP, null, null,
                CertMode.ACME_DNS, null, true, null, null);
        JsonNode domains = yaml.readTree(render(List.of(r))).at("/http/routers/wildcard-example-com/tls/domains");
        assertThat(domains.size()).isEqualTo(1);
        assertThat(domains.get(0).get("main").asString()).isEqualTo("*.example.com");
        assertThat(domains.get(0).get("sans").get(0).asString()).isEqualTo("example.com");
    }

    @Test
    void internalCertificate() {
        assertThat(render(List.of(route("app.example.com", "app", 80, CertMode.INTERNAL))))
                .isEqualTo(golden("traefik/internal.yml"));
    }

    @Test
    void customCertificate() {
        ProxyRoute r = full("app.example.com", List.of(), "app", 80, BackendScheme.H2C, null, null, CertMode.CUSTOM,
                CERT_ID, true, null, null);
        assertThat(render(List.of(r))).isEqualTo(golden("traefik/custom.yml"));
    }

    @Test
    void routesAreOrderedByHostnameAndOutputIsDeterministic() {
        ProxyRoute b = route("b.example.com", "b", 80, CertMode.ACME_HTTP);
        ProxyRoute a = route("a.example.com", "a", 80, CertMode.ACME_HTTP);
        String first = render(List.of(b, a));
        assertThat(first).isEqualTo(golden("traefik/ordering.yml"));
        assertThat(render(List.of(a, b))).isEqualTo(first);
    }

    @Test
    void customCertificateMustBeProvided() {
        ProxyRoute r = full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, null, null, CertMode.CUSTOM,
                UUID.randomUUID(), true, null, null);
        assertThatThrownBy(() -> render(List.of(r))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hostileHeaderValuesStayInsideTheirYamlStringAndTemplatesAreNeutralised() throws Exception {
        String hostile = "x\\\" ' # ; : % {} {{ .Env }} } ${HOME} `tick` <b> end";
        ProxyRoute r = full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, null, null,
                CertMode.ACME_HTTP, null, true, null, new TreeMap<>(Map.of("X-Hostile", hostile)));
        String out = render(List.of(r));

        assertThat(out).doesNotContain("{{");
        assertThat(out.lines().filter(l -> l.contains("X-Hostile"))).hasSize(1);
        JsonNode parsed = yaml.readTree(out);
        assertThat(parsed.at("/http/middlewares/app-example-com--headers/headers/customResponseHeaders/X-Hostile")
                .asString()).isEqualTo(hostile);
        assertThat(parsed.at("/http/routers").size()).isEqualTo(2);
        assertThat(parsed.at("/http/services").size()).isEqualTo(1);
    }

    @Test
    void controlAndNonAsciiCharactersInAnyValueAreRejected() {
        for (String bad : List.of("a\nb", "a\rb", "a\u0000b", "a\u2028b", "caf\u00e9", "a\u0085b", "\uD83D\uDE00")) {
            ProxyRoute header = full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, null, null,
                    CertMode.ACME_HTTP, null, true, null, Map.of("X-A", bad));
            assertThatThrownBy(() -> render(List.of(header))).as("header %s", bad.length())
                    .isInstanceOf(IllegalArgumentException.class);
            ProxyRoute user = full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, null, null,
                    CertMode.ACME_HTTP, null, true, Map.of("u" + bad, HASH), null);
            assertThatThrownBy(() -> render(List.of(user))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void hostnameContainerPathAndEntrypointOutsideTheirCharsetAreRejected() {
        List<ProxyRoute> hostile = List.of(
                route("app.example.com`) || Host(`evil.com", "app", 80, CertMode.ACME_HTTP),
                route("app.example.com\nevil: x", "app", 80, CertMode.ACME_HTTP),
                route("app.example.com", "app\n  - url: x", 80, CertMode.ACME_HTTP),
                full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, "/a`)||Host(`x", null,
                        CertMode.ACME_HTTP, null, true, null, null),
                full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, null, "web\"]\nx: [\"",
                        CertMode.ACME_HTTP, null, true, null, null),
                full("app.example.com", List.of("b.example.com\n"), "app", 80, BackendScheme.HTTP, null, null,
                        CertMode.ACME_HTTP, null, true, null, null),
                full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, null, null, CertMode.ACME_HTTP,
                        null, true, Map.of("alice", "$2a$10$bad\nhash"), null),
                full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, null, null, CertMode.ACME_HTTP,
                        null, true, null, Map.of("X-A\nb", "v")));
        for (ProxyRoute r : hostile) {
            assertThatThrownBy(() -> render(List.of(r))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
