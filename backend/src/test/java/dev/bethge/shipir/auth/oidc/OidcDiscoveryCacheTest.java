package dev.bethge.shipir.auth.oidc;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OidcDiscoveryCacheTest {

    private static final OidcDiscoveryClient.Metadata META =
            new OidcDiscoveryClient.Metadata("https://idp", "https://idp/a", "https://idp/t", "https://idp/j");

    private final OidcDiscoveryClient client = mock(OidcDiscoveryClient.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2030-01-01T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final OidcDiscoveryCache cache = new OidcDiscoveryCache(client, clock, Duration.ofMinutes(10));

    @Test
    void secondLookupWithinTheTtlDoesNotFetchAgain() {
        when(client.fetch("https://idp")).thenReturn(META);

        assertThat(cache.get("https://idp")).isEqualTo(META);
        now.set(now.get().plus(Duration.ofMinutes(9)));
        assertThat(cache.get("https://idp")).isEqualTo(META);

        verify(client, times(1)).fetch("https://idp");
    }

    @Test
    void lookupAfterTheTtlFetchesAgain() {
        when(client.fetch("https://idp")).thenReturn(META);

        cache.get("https://idp");
        now.set(now.get().plus(Duration.ofMinutes(10)));
        cache.get("https://idp");

        verify(client, times(2)).fetch("https://idp");
    }

    @Test
    void invalidateForcesANewFetchAndAnotherIssuerIsNotServedFromTheCache() {
        when(client.fetch("https://idp")).thenReturn(META);
        when(client.fetch("https://other")).thenReturn(META);

        cache.get("https://idp");
        cache.get("https://other");
        cache.invalidate();
        cache.get("https://idp");

        verify(client, times(2)).fetch("https://idp");
        verify(client, times(1)).fetch("https://other");
    }

    @Test
    void failuresAreNotCached() {
        when(client.fetch("https://idp")).thenThrow(new OidcException("PROVIDER_UNREACHABLE", "x")).thenReturn(META);

        assertThatThrownBy(() -> cache.get("https://idp")).isInstanceOf(OidcException.class);
        assertThat(cache.get("https://idp")).isEqualTo(META);
    }
}
