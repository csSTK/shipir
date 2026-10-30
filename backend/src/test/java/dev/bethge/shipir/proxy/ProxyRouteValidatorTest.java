package dev.bethge.shipir.proxy;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyRouteValidatorTest {

    private static final UUID HOST = UUID.randomUUID();
    private static final String BCRYPT = "$2a$10$" + "a".repeat(53);

    private final ProxyRouteValidator validator = new ProxyRouteValidator();

    private static RouteInput.Builder route() {
        return RouteInput.builder().hostname("app.example.com").containerName("web").targetPort(8080)
                .backendScheme(BackendScheme.HTTP).certMode(CertMode.ACME_HTTP);
    }

    private void expect(RouteInput in, ProxyType type, String code) {
        assertThatThrownBy(() -> validator.validate(HOST, type, in, Set.of(), null))
                .isInstanceOfSatisfying(ProxyException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private void expect(RouteInput in, String code) {
        expect(in, ProxyType.TRAEFIK, code);
    }

    @Test
    void acceptsAMinimalRouteOnBothProxies() {
        assertThatCode(() -> validator.validate(HOST, ProxyType.TRAEFIK, route().build(), Set.of(), null)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(HOST, ProxyType.CADDY, route().build(), Set.of(), null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"App.Example.com", "app.example.com.", "bücher.example.com", "localhost", "a..b", "app.example.com\r\nX: y"})
    void rejectsInvalidHostnames(String hostname) {
        expect(route().hostname(hostname).build(), "INVALID_HOSTNAME");
    }

    @Test
    void acceptsPunycode() {
        assertThatCode(() -> validator.validate(HOST, ProxyType.TRAEFIK,
                route().hostname("xn--bcher-kva.example.com").build(), Set.of(), null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNamesLongerThanTheColumn() {
        String base = "a.".repeat(125) + "com"; // 253 chars, valid on its own
        assertThat(base).hasSize(253);
        expect(route().hostname("*." + base).certMode(CertMode.ACME_DNS).build(), "INVALID_HOSTNAME");
        expect(route().aliases(List.of("*." + base)).certMode(CertMode.ACME_DNS).build(), "INVALID_HOSTNAME");
    }

    @Test
    void rejectsInvalidAlias() {
        expect(route().aliases(List.of("Www.Example.com")).build(), "INVALID_HOSTNAME");
    }

    @Test
    void wildcardNeedsDnsChallengeOrCoveringCustomCert() {
        expect(route().hostname("*.example.com").build(), "WILDCARD_NOT_ALLOWED");
        expect(route().hostname("*.example.com").certMode(CertMode.INTERNAL).build(), "WILDCARD_NOT_ALLOWED");
        assertThatCode(() -> validator.validate(HOST, ProxyType.TRAEFIK,
                route().hostname("*.example.com").certMode(CertMode.ACME_DNS).build(), Set.of(), null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*example.com", "*.com", "a.*.example.com", "**.example.com", "*.*.example.com", "*."})
    void rejectsMalformedWildcards(String hostname) {
        expect(route().hostname(hostname).certMode(CertMode.ACME_DNS).build(), "INVALID_HOSTNAME");
    }

    @Test
    void caddyRejectsDnsChallenge() {
        expect(route().certMode(CertMode.ACME_DNS).build(), ProxyType.CADDY, "UNSUPPORTED_FOR_PROXY");
    }

    @Test
    void caddyRejectsEntrypoint() {
        expect(route().entrypoint("websecure").build(), ProxyType.CADDY, "UNSUPPORTED_FOR_PROXY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"web secure", "web\nsecure", "-web", "web`x`", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void rejectsBadEntrypointNames(String name) {
        expect(route().entrypoint(name).build(), "INVALID_ENTRYPOINT");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536})
    void rejectsPortOutOfRange(int port) {
        expect(route().targetPort(port).build(), "INVALID_PORT");
    }

    @Test
    void rejectsMissingPortOrScheme() {
        expect(route().targetPort(null).build(), "INVALID_PORT");
        expect(route().backendScheme(null).build(), "INVALID_ROUTE");
        expect(route().certMode(null).build(), "INVALID_ROUTE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "/../etc", "/a/../b", "/a/..", "/a b", "/a\nb", "/a?x=1", "//x", "//", "/a//b", "/a\"b", "/`x`", "/a/%2e%2e/b"})
    void rejectsBadPathPrefixes(String prefix) {
        expect(route().pathPrefix(prefix).build(), "INVALID_PATH_PREFIX");
    }

    @Test
    void acceptsGoodPathPrefix() {
        assertThatCode(() -> validator.validate(HOST, ProxyType.TRAEFIK, route().pathPrefix("/api/v1").build(), Set.of(), null))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-x", "a b", "a;rm", "a/b", "$(x)"})
    void rejectsBadContainerNames(String name) {
        expect(route().containerName(name).build(), "INVALID_CONTAINER");
    }

    @Test
    void duplicateHostnameOnSameHostIsTaken() {
        assertThatThrownBy(() -> validator.validate(HOST, ProxyType.TRAEFIK, route().build(), Set.of("app.example.com"), null))
                .isInstanceOfSatisfying(ProxyException.class, e -> assertThat(e.code()).isEqualTo("ROUTE_HOST_TAKEN"));
    }

    @Test
    void aliasCollidingWithAnotherRouteIsTaken() {
        assertThatThrownBy(() -> validator.validate(HOST, ProxyType.TRAEFIK,
                route().aliases(List.of("www.example.com")).build(), Set.of("www.example.com"), null))
                .isInstanceOfSatisfying(ProxyException.class, e -> assertThat(e.code()).isEqualTo("ROUTE_HOST_TAKEN"));
    }

    @Test
    void aliasEqualToOwnHostnameOrDuplicateAliasIsTaken() {
        expect(route().aliases(List.of("app.example.com")).build(), "ROUTE_HOST_TAKEN");
        expect(route().aliases(List.of("a.example.com", "a.example.com")).build(), "ROUTE_HOST_TAKEN");
    }

    @Test
    void customRequiresCertificateOfTheSameHostCoveringAllNames() {
        RouteInput in = route().certMode(CertMode.CUSTOM).aliases(List.of("www.example.com")).build();
        expect(in, "CERT_REQUIRED");

        ProxyCertificate other = cert(UUID.randomUUID(), "app.example.com", "www.example.com");
        assertThatThrownBy(() -> validator.validate(HOST, ProxyType.TRAEFIK, in, Set.of(), other))
                .isInstanceOfSatisfying(ProxyException.class, e -> assertThat(e.code()).isEqualTo("CERT_NOT_FOUND"));

        ProxyCertificate partial = cert(HOST, "app.example.com");
        assertThatThrownBy(() -> validator.validate(HOST, ProxyType.TRAEFIK, in, Set.of(), partial))
                .isInstanceOfSatisfying(ProxyException.class, e -> assertThat(e.code()).isEqualTo("CERT_DOES_NOT_COVER_HOST"));

        assertThatCode(() -> validator.validate(HOST, ProxyType.TRAEFIK, in, Set.of(), cert(HOST, "app.example.com", "www.example.com")))
                .doesNotThrowAnyException();
    }

    @Test
    void wildcardCertCoversExactlyOneLabelBelow() {
        RouteInput one = route().certMode(CertMode.CUSTOM).build();
        assertThatCode(() -> validator.validate(HOST, ProxyType.CADDY, one, Set.of(), cert(HOST, "*.example.com")))
                .doesNotThrowAnyException();
        RouteInput deep = route().hostname("a.app.example.com").certMode(CertMode.CUSTOM).build();
        assertThatThrownBy(() -> validator.validate(HOST, ProxyType.CADDY, deep, Set.of(), cert(HOST, "*.example.com")))
                .isInstanceOfSatisfying(ProxyException.class, e -> assertThat(e.code()).isEqualTo("CERT_DOES_NOT_COVER_HOST"));
        RouteInput apex = route().hostname("example.com").certMode(CertMode.CUSTOM).build();
        assertThatThrownBy(() -> validator.validate(HOST, ProxyType.CADDY, apex, Set.of(), cert(HOST, "*.example.com")))
                .isInstanceOfSatisfying(ProxyException.class, e -> assertThat(e.code()).isEqualTo("CERT_DOES_NOT_COVER_HOST"));
    }

    @Test
    void wildcardRouteWithCustomCertNeedsTheSameWildcardInTheCert() {
        RouteInput in = route().hostname("*.example.com").certMode(CertMode.CUSTOM).build();
        assertThatCode(() -> validator.validate(HOST, ProxyType.CADDY, in, Set.of(), cert(HOST, "*.example.com")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(HOST, ProxyType.CADDY, in, Set.of(), cert(HOST, "a.example.com")))
                .isInstanceOfSatisfying(ProxyException.class, e -> assertThat(e.code()).isEqualTo("CERT_DOES_NOT_COVER_HOST"));
    }

    @Test
    void certificateWithNonCustomModeIsRejected() {
        assertThatThrownBy(() -> validator.validate(HOST, ProxyType.TRAEFIK, route().build(), Set.of(), cert(HOST, "app.example.com")))
                .isInstanceOfSatisfying(ProxyException.class, e -> assertThat(e.code()).isEqualTo("INVALID_ROUTE"));
    }

    @Test
    void basicAuthAcceptsOnlyBcryptHashes() {
        assertThatCode(() -> validator.validate(HOST, ProxyType.TRAEFIK,
                route().basicAuthUsers(Map.of("alice", BCRYPT)).build(), Set.of(), null)).doesNotThrowAnyException();
        expect(route().basicAuthUsers(Map.of("alice", "hunter2")).build(), "INVALID_BASIC_AUTH");
        expect(route().basicAuthUsers(Map.of("alice", "{SHA}abc")).build(), "INVALID_BASIC_AUTH");
        expect(route().basicAuthUsers(Map.of("al:ice", BCRYPT)).build(), "INVALID_BASIC_AUTH");
        expect(route().basicAuthUsers(Map.of("al\nice", BCRYPT)).build(), "INVALID_BASIC_AUTH");
    }

    @Test
    void headersRejectControlCharactersAndBadNames() {
        expect(route().responseHeaders(Map.of("X-Test", "a\r\nSet-Cookie: x=1")).build(), "INVALID_HEADER");
        expect(route().responseHeaders(Map.of("X-Test", "a\nb")).build(), "INVALID_HEADER");
        expect(route().responseHeaders(Map.of("X Test", "a")).build(), "INVALID_HEADER");
        expect(route().responseHeaders(Map.of("X-Test\r\n", "a")).build(), "INVALID_HEADER");
        expect(route().responseHeaders(Map.of("X-Test", "a\"b")).build(), "INVALID_HEADER");
        expect(route().responseHeaders(Map.of("X-Test", "a".repeat(513))).build(), "INVALID_HEADER");
        assertThatCode(() -> validator.validate(HOST, ProxyType.TRAEFIK,
                route().responseHeaders(Map.of("X-Frame-Options", "SAMEORIGIN")).build(), Set.of(), null)).doesNotThrowAnyException();
    }

    @Test
    void capsAliasesBasicAuthUsersAndHeaders() {
        List<String> aliases = new java.util.ArrayList<>();
        Map<String, String> users = new java.util.LinkedHashMap<>();
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (int i = 0; i <= ProxyRouteValidator.MAX_ENTRIES; i++) {
            aliases.add("a" + i + ".example.com");
            users.put("user" + i, BCRYPT);
            headers.put("X-H" + i, "v");
        }
        expect(route().aliases(aliases).build(), "TOO_MANY_ALIASES");
        expect(route().basicAuthUsers(users).build(), "TOO_MANY_USERS");
        expect(route().responseHeaders(headers).build(), "TOO_MANY_HEADERS");
        assertThatCode(() -> validator.validate(HOST, ProxyType.TRAEFIK,
                route().aliases(aliases.subList(0, ProxyRouteValidator.MAX_ENTRIES)).build(), Set.of(), null)).doesNotThrowAnyException();
    }

    private static ProxyCertificate cert(UUID host, String... domains) {
        return ProxyCertificate.create(host, "c", "PEM", new byte[]{1}, List.of(domains), java.time.Instant.now().plusSeconds(60));
    }
}
