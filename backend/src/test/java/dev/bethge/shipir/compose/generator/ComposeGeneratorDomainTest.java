package dev.bethge.shipir.compose.generator;

import dev.bethge.shipir.compose.generator.model.ComposeSpec;
import dev.bethge.shipir.compose.generator.model.DomainSpec;
import dev.bethge.shipir.compose.generator.model.NetworkSpec;
import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.compose.generator.model.ServiceSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ComposeGeneratorDomainTest {

    private final ComposeGeneratorService generator = new ComposeGeneratorService();

    private static ServiceSpec app(DomainSpec domain, List<String> networks, Map<String, String> labels) {
        return new ServiceSpec("app", "ghost:5", null, null, null, null, null, networks, labels, null, domain);
    }

    private static ComposeSpec spec(ServiceSpec s, List<NetworkSpec> networks) {
        return new ComposeSpec(List.of(s), networks, null);
    }

    @Test
    void goldenTraefikLabelsAndProxyNetwork() {
        String yaml = generator.generate(spec(
                app(new DomainSpec("blog.example.com", 2368, ProxyType.TRAEFIK, "proxy"), null, null), null));
        assertThat(yaml).isEqualTo("""
                services:
                  app:
                    image: ghost:5
                    networks:
                      - default
                      - proxy
                    labels:
                      traefik.enable: "true"
                      traefik.docker.network: proxy
                      traefik.http.routers.blog-example-com.rule: Host(`blog.example.com`)
                      traefik.http.routers.blog-example-com.entrypoints: websecure
                      traefik.http.routers.blog-example-com.tls.certresolver: letsencrypt
                      traefik.http.services.blog-example-com.loadbalancer.server.port: "2368"
                networks:
                  proxy:
                    external: true
                """);
    }

    @Test
    void goldenCaddyLabelsAndProxyNetwork() {
        String yaml = generator.generate(spec(
                app(new DomainSpec("blog.example.com", 2368, ProxyType.CADDY, "caddy"), null, null), null));
        assertThat(yaml).isEqualTo("""
                services:
                  app:
                    image: ghost:5
                    networks:
                      - default
                      - caddy
                    labels:
                      caddy: blog.example.com
                      caddy.reverse_proxy: "{{upstreams 2368}}"
                networks:
                  caddy:
                    external: true
                """);
    }

    @Test
    void withoutProxyNetworkOnlyLabelsAreAdded() {
        String yaml = generator.generate(spec(
                app(new DomainSpec("a.example.org", 80, ProxyType.CADDY, null), null, null), null));
        assertThat(yaml).doesNotContain("networks:").contains("caddy: a.example.org");
    }

    @Test
    void keepsExplicitNetworksAndDeclaredProxyNetwork() {
        String yaml = generator.generate(spec(
                app(new DomainSpec("a.example.org", 80, ProxyType.TRAEFIK, "proxy"), List.of("back"), null),
                List.of(new NetworkSpec("back", null, false), new NetworkSpec("proxy", null, true))));
        assertThat(yaml).contains("      - back\n      - proxy\n").doesNotContain("- default");
        assertThat(yaml.split("\n  proxy:", -1)).hasSize(2);
    }

    @Test
    void userLabelsAreKeptAlongsideDomainLabels() {
        String yaml = generator.generate(spec(
                app(new DomainSpec("a.example.org", 80, ProxyType.CADDY, null), null, Map.of("tier", "web")), null));
        assertThat(yaml).contains("tier: web").contains("caddy: a.example.org");
    }

    private List<String> violations(DomainSpec domain, Map<String, String> labels) {
        return catchThrowableOfType(InvalidComposeSpecException.class,
                () -> generator.generate(spec(app(domain, null, labels), null))).violations();
    }

    @Test
    void domainLabelCollidingWithUserLabelIsRejected() {
        assertThat(violations(new DomainSpec("a.example.org", 80, ProxyType.CADDY, null), Map.of("caddy", "x")))
                .anyMatch(v -> v.contains("label caddy"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "UPPER.example.com", "no_underscore.example.com", "a b.example.com",
            "x.example.com`)||Host(`y", "localhost", "-a.example.com", "a..example.com", "http://a.example.com",
            "a.example.com/path"})
    void invalidHostIsRejected(String host) {
        assertThat(violations(new DomainSpec(host, 80, ProxyType.TRAEFIK, null), null))
                .anyMatch(v -> v.contains("domain.host"));
    }

    @Test
    void invalidPortProxyAndNetworkAreRejected() {
        assertThat(violations(new DomainSpec("a.example.org", 0, ProxyType.TRAEFIK, null), null))
                .anyMatch(v -> v.contains("domain.port"));
        assertThat(violations(new DomainSpec("a.example.org", null, ProxyType.TRAEFIK, null), null))
                .anyMatch(v -> v.contains("domain.port"));
        assertThat(violations(new DomainSpec("a.example.org", 80, null, null), null))
                .anyMatch(v -> v.contains("domain.proxy"));
        assertThat(violations(new DomainSpec("a.example.org", 80, ProxyType.TRAEFIK, "bad name\n"), null))
                .anyMatch(v -> v.contains("domain.network"));
    }

    private ServiceSpec svc(String name, String host) {
        return new ServiceSpec(name, "nginx", null, null, null, null, null, null, null, null,
                new DomainSpec(host, 80, ProxyType.TRAEFIK, null));
    }

    private List<String> violations(ServiceSpec... services) {
        return catchThrowableOfType(InvalidComposeSpecException.class,
                () -> generator.generate(new ComposeSpec(List.of(services), null, null))).violations();
    }

    @Test
    void sameHostOnTwoServicesIsRejected() {
        assertThat(violations(svc("a", "x.example.com"), svc("b", "x.example.com")))
                .anyMatch(v -> v.contains("services.b.domain.host") && v.contains("already used"));
    }

    @Test
    void traefikRouterNameCollisionIsRejected() {
        assertThat(violations(svc("a", "a-b.example.com"), svc("b", "a.b.example.com")))
                .anyMatch(v -> v.contains("services.b.domain.host") && v.contains("router"));
    }

    @Test
    void distinctHostsOnTwoServicesAreAccepted() {
        assertThat(generator.generate(new ComposeSpec(
                List.of(svc("a", "a.example.com"), svc("b", "b.example.com")), null, null)))
                .contains("a-example-com").contains("b-example-com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Proxy", "traefik.net", "My_Net-1", "0abc"})
    void dockerNetworkNamesWithUppercaseAndDotsAreAccepted(String network) {
        assertThat(generator.generate(spec(
                app(new DomainSpec("a.example.org", 80, ProxyType.TRAEFIK, network), null, null), null)))
                .contains("traefik.docker.network: " + network).contains("external: true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-x", ".x", "a b", "a\nb", "a:b", "a/b", "a`b", "x\"y"})
    void injectionProneNetworkNamesAreRejected(String network) {
        assertThat(violations(new DomainSpec("a.example.org", 80, ProxyType.TRAEFIK, network), null))
                .anyMatch(v -> v.contains("domain.network"));
    }

    @Test
    void defaultNetworkIsRejectedBecauseComposeWouldDeclareItExternal() {
        // "default" is Compose's own project network; declaring it external yields a broken network reference.
        assertThat(violations(new DomainSpec("a.example.org", 80, ProxyType.TRAEFIK, "default"), null))
                .anyMatch(v -> v.contains("domain.network"));
        assertThat(violations(new DomainSpec("a.example.org", 80, ProxyType.CADDY, "default"), null))
                .anyMatch(v -> v.contains("domain.network"));
    }
}
