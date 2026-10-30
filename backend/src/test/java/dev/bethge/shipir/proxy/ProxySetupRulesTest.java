package dev.bethge.shipir.proxy;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.proxy.dto.ProxySetupRequest;
import dev.bethge.shipir.proxy.dto.ProxyUpdateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxySetupRulesTest {

    private static ProxySetupRequest traefik(CertMode mode, String provider, Map<String, String> creds) {
        return new ProxySetupRequest(ProxyType.TRAEFIK, "ops@example.com", mode, provider, creds);
    }

    private static void expect(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ProxyException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.status().value()).isEqualTo(400);
        });
    }

    @Test
    void acceptsPlainTraefikAndCaddySetups() {
        assertThatCode(() -> ProxySetupRules.validate(traefik(CertMode.ACME_HTTP, null, null))).doesNotThrowAnyException();
        assertThatCode(() -> ProxySetupRules.validate(
                new ProxySetupRequest(ProxyType.CADDY, null, CertMode.INTERNAL, null, null))).doesNotThrowAnyException();
    }

    @Test
    void caddyRejectsTheDnsChallengeAndDnsCredentials() {
        expect(() -> ProxySetupRules.validate(new ProxySetupRequest(ProxyType.CADDY, "a@b.de", CertMode.ACME_DNS,
                "cloudflare", Map.of("CF_DNS_API_TOKEN", "x"))), "UNSUPPORTED_FOR_PROXY");
        expect(() -> ProxySetupRules.validate(new ProxySetupRequest(ProxyType.CADDY, "a@b.de", CertMode.ACME_HTTP,
                "cloudflare", Map.of("CF_DNS_API_TOKEN", "x"))), "UNSUPPORTED_FOR_PROXY");
    }

    @Test
    void dnsModeNeedsProviderAndCredentials() {
        expect(() -> ProxySetupRules.validate(traefik(CertMode.ACME_DNS, null, Map.of("A_B", "x"))), "DNS_CREDENTIALS_REQUIRED");
        expect(() -> ProxySetupRules.validate(traefik(CertMode.ACME_DNS, "cloudflare", Map.of())), "DNS_CREDENTIALS_REQUIRED");
        assertThatCode(() -> ProxySetupRules.validate(traefik(CertMode.ACME_DNS, "cloudflare", Map.of("CF_DNS_API_TOKEN", "t"))))
                .doesNotThrowAnyException();
    }

    @Test
    void customCertificateCannotBeTheDefault() {
        expect(() -> ProxySetupRules.validate(traefik(CertMode.CUSTOM, null, null)), "INVALID_CERT_MODE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no-at-sign", "a@b", "a b@example.com", "a@example.com\nX=1", "a@example.com'; rm"})
    void traefikNeedsAValidEmail(String email) {
        expect(() -> ProxySetupRules.validate(
                new ProxySetupRequest(ProxyType.TRAEFIK, email, CertMode.ACME_HTTP, null, null)), "INVALID_EMAIL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Cloud Flare", "cloud;flare", "x", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "../etc"})
    void rejectsOddProviderNames(String provider) {
        expect(() -> ProxySetupRules.validate(traefik(CertMode.ACME_HTTP, provider, Map.of("A_B", "x"))), "INVALID_DNS_PROVIDER");
    }

    @Test
    void capsAndShapesTheCredentialMap() {
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i <= ProxySetupRules.MAX_CREDENTIALS; i++) {
            many.put("KEY_" + i, "v");
        }
        expect(() -> ProxySetupRules.validate(traefik(CertMode.ACME_HTTP, "cloudflare", many)), "INVALID_DNS_CREDENTIALS");
        expect(() -> ProxySetupRules.validate(traefik(CertMode.ACME_HTTP, "cloudflare", Map.of("lower", "x"))), "INVALID_DNS_CREDENTIALS");
        expect(() -> ProxySetupRules.validate(traefik(CertMode.ACME_HTTP, "cloudflare", Map.of("ACME_EMAIL", "x"))), "INVALID_DNS_CREDENTIALS");
        expect(() -> ProxySetupRules.validate(traefik(CertMode.ACME_HTTP, "cloudflare", Map.of("DNS_PROVIDER", "x"))), "INVALID_DNS_CREDENTIALS");
        expect(() -> ProxySetupRules.validate(traefik(CertMode.ACME_HTTP, "cloudflare", Map.of("A_B", "x\ny"))), "INVALID_DNS_CREDENTIALS");
        expect(() -> ProxySetupRules.validate(traefik(CertMode.ACME_HTTP, "cloudflare", Map.of("A_B", "x".repeat(2001)))), "INVALID_DNS_CREDENTIALS");
        expect(() -> ProxySetupRules.validate(traefik(CertMode.ACME_HTTP, "cloudflare", Map.of("A_B", ""))), "INVALID_DNS_CREDENTIALS");
    }

    @Test
    void requestToStringHidesCredentials() {
        String s = traefik(CertMode.ACME_DNS, "cloudflare", Map.of("CF_DNS_API_TOKEN", "super-secret-token")).toString();
        assertThat(s).doesNotContain("super-secret-token");
        assertThat(new ProxyUpdateRequest(null, null, "cloudflare", Map.of("CF_DNS_API_TOKEN", "super-secret-token"), null)
                .toString()).doesNotContain("super-secret-token");
    }

    @Test
    void updateRejectsAmbiguousCredentialChanges() {
        expect(() -> ProxySetupRules.validate(new ProxyUpdateRequest(null, null, null, Map.of("A_B", "x"), true)),
                "INVALID_DNS_CREDENTIALS");
        assertThatCode(() -> ProxySetupRules.validate(new ProxyUpdateRequest(null, null, null, null, null)))
                .doesNotThrowAnyException();
    }
}
