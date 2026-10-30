package dev.bethge.shipir.proxy.render;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.proxy.BackendScheme;
import dev.bethge.shipir.proxy.CertMode;
import dev.bethge.shipir.proxy.ProxyRoute;
import org.junit.jupiter.api.Test;

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

class CaddyConfigRendererTest {

    private final CaddyConfigRenderer renderer = new CaddyConfigRenderer();

    private String render(String email, List<ProxyRoute> routes) {
        List<RenderedFile> files = renderer.render(instance(ProxyType.CADDY, email), routes,
                Map.of(CERT_ID, certificate()));
        assertThat(files).extracting(RenderedFile::name).containsExactly("Caddyfile");
        return files.get(0).content();
    }

    @Test
    void minimalRoute() {
        assertThat(render("admin@example.com", List.of(route("app.example.com", "app", 8080, CertMode.ACME_HTTP))))
                .isEqualTo(golden("caddy/minimal.caddy"));
    }

    @Test
    void withoutEmailTheGlobalBlockHasNoEmail() {
        assertThat(render(null, List.of(route("app.example.com", "app", 8080, CertMode.ACME_HTTP))))
                .isEqualTo(golden("caddy/no-email.caddy"));
    }

    @Test
    void allAdvancedOptions() {
        ProxyRoute r = full("api.example.com", List.of("www.example.com"), "api-1", 8443, BackendScheme.HTTPS, "/v1/",
                null, CertMode.ACME_HTTP, null, false, Map.of("alice", HASH),
                Map.of("X-Test", "a;b # c: 'd' %", "X-Frame-Options", "DENY"));
        assertThat(render("admin@example.com", List.of(r))).isEqualTo(golden("caddy/advanced.caddy"));
    }

    @Test
    void internalCertificate() {
        assertThat(render(null, List.of(route("app.example.com", "app", 80, CertMode.INTERNAL))))
                .isEqualTo(golden("caddy/internal.caddy"));
    }

    @Test
    void customCertificateWithH2cBackendAndWildcardName() {
        ProxyRoute r = full("*.example.com", List.of(), "app", 80, BackendScheme.H2C, null, null, CertMode.CUSTOM,
                CERT_ID, true, null, null);
        assertThat(render(null, List.of(r))).isEqualTo(golden("caddy/custom.caddy"));
    }

    @Test
    void routesAreOrderedByHostnameAndOutputIsDeterministic() {
        ProxyRoute b = route("b.example.com", "b", 80, CertMode.ACME_HTTP);
        ProxyRoute a = route("a.example.com", "a", 80, CertMode.ACME_HTTP);
        String first = render(null, List.of(b, a));
        assertThat(first).isEqualTo(golden("caddy/ordering.caddy"));
        assertThat(render(null, List.of(a, b))).isEqualTo(first);
    }

    @Test
    void dnsChallengeAndEntrypointsAreNotSupported() {
        assertThatThrownBy(() -> render(null, List.of(route("app.example.com", "app", 80, CertMode.ACME_DNS))))
                .isInstanceOf(IllegalArgumentException.class);
        ProxyRoute ep = full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, null, "web",
                CertMode.ACME_HTTP, null, true, null, null);
        assertThatThrownBy(() -> render(null, List.of(ep))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customCertificateMustBeProvided() {
        ProxyRoute r = full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, null, null, CertMode.CUSTOM,
                UUID.randomUUID(), true, null, null);
        assertThatThrownBy(() -> render(null, List.of(r))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hostileHeaderValuesCannotLeaveTheirQuotedToken() {
        String hostile = "x\" ' # ; : % {} {env.SECRET} } `tick` <b> end";
        ProxyRoute r = full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, null, null,
                CertMode.ACME_HTTP, null, true, null, new TreeMap<>(Map.of("X-Hostile", hostile)));
        String out = render(null, List.of(r));

        assertThat(out).isEqualTo(golden("caddy/hostile.caddy"));
        // one line per directive: the hostile value added no line, no brace opens or closes inside it
        assertThat(out.lines().filter(l -> l.contains("X-Hostile"))).hasSize(1);
        assertThat(out.lines().count()).isEqualTo(golden("caddy/hostile.caddy").lines().count());
    }

    /** Caddy keeps a backslash literal except before a quote, so a trailing one would swallow the closing quote. */
    @Test
    void backslashesAndEnvironmentSubstitutionInValuesAreRejected() {
        for (String bad : List.of("trail\\", "mid\\dle", "\\\\", "\\\"", "{$HOME}", "x{$HOME", "\\{$HOME}")) {
            ProxyRoute header = full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, null, null,
                    CertMode.ACME_HTTP, null, true, null, Map.of("X-A", bad));
            assertThatThrownBy(() -> render(null, List.of(header))).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> render("a@b.c\\", List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void controlAndNonAsciiCharactersInAnyValueAreRejected() {
        for (String bad : List.of("a\nb", "a\rb", "a\u0000b", "a\u2028b", "caf\u00e9", "a\u0085b", "\uD83D\uDE00")) {
            ProxyRoute header = full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, null, null,
                    CertMode.ACME_HTTP, null, true, null, Map.of("X-A", bad));
            assertThatThrownBy(() -> render(null, List.of(header))).isInstanceOf(IllegalArgumentException.class);
            ProxyRoute user = full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, null, null,
                    CertMode.ACME_HTTP, null, true, Map.of("u" + bad, HASH), null);
            assertThatThrownBy(() -> render(null, List.of(user))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> render("a@b.c" + bad, List.of())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void hostnameContainerAndPathOutsideTheirCharsetAreRejected() {
        List<ProxyRoute> hostile = List.of(
                route("app.example.com {\n\treverse_proxy evil:80\n}\nx.com", "app", 80, CertMode.ACME_HTTP),
                route("app.example.com", "app:80\n", 80, CertMode.ACME_HTTP),
                route("app.example.com", "app evil", 80, CertMode.ACME_HTTP),
                full("app.example.com", List.of("b.example.com\n"), "app", 80, BackendScheme.HTTP, null, null,
                        CertMode.ACME_HTTP, null, true, null, null),
                full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, "/a {\n", null, CertMode.ACME_HTTP,
                        null, true, null, null),
                full("app.example.com", List.of(), "app", 80, BackendScheme.HTTP, null, null, CertMode.ACME_HTTP,
                        null, true, null, Map.of("X-A b", "v")));
        for (ProxyRoute r : hostile) {
            assertThatThrownBy(() -> render(null, List.of(r))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
