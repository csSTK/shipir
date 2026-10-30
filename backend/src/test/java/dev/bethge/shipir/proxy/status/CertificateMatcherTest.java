package dev.bethge.shipir.proxy.status;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CertificateMatcherTest {

    @Test
    void exactNameMatchesIgnoringCase() {
        assertThat(CertificateMatcher.covers("shop.example.com", "Shop.Example.com")).isTrue();
        assertThat(CertificateMatcher.covers("shop.example.com", "other.example.com")).isFalse();
    }

    @Test
    void wildcardCoversExactlyOneLabel() {
        assertThat(CertificateMatcher.covers("*.example.com", "shop.example.com")).isTrue();
        assertThat(CertificateMatcher.covers("*.example.com", "example.com")).isFalse();
        assertThat(CertificateMatcher.covers("*.example.com", "a.b.example.com")).isFalse();
        assertThat(CertificateMatcher.covers("*.example.com", "evilexample.com")).isFalse();
        assertThat(CertificateMatcher.covers("*.example.com", "shop.example.com.evil.org")).isFalse();
    }

    @Test
    void wildcardRequestHostsMatchOnlyTheSameWildcard() {
        assertThat(CertificateMatcher.covers("*.example.com", "*.example.com")).isTrue();
        assertThat(CertificateMatcher.covers("shop.example.com", "*.example.com")).isFalse();
    }

    @Test
    void bestPicksTheCoveringCertificateThatEndsLast() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        IssuedCert early = new IssuedCert(List.of("shop.example.com"), now.plusSeconds(100));
        IssuedCert late = new IssuedCert(List.of("*.example.com"), now.plusSeconds(900));
        IssuedCert unrelated = new IssuedCert(List.of("x.org"), now.plusSeconds(5000));

        assertThat(CertificateMatcher.best(List.of(early, late, unrelated), "shop.example.com")).contains(late);
        assertThat(CertificateMatcher.best(List.of(unrelated), "shop.example.com")).isEmpty();
    }
}
