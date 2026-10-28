package dev.bethge.shipir.auth.oidc;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Time-limited cache of the provider's discovery document for the sign-in flow, so the public {@code authorize}
 * endpoint does not make an outbound request per call. Holds one entry (there is one configured provider), keyed by
 * the issuer; failures are never cached. {@link #invalidate} is called when the OIDC settings are saved. The
 * "Test connection" button deliberately bypasses this cache and uses {@link OidcDiscoveryClient#fetch} directly.
 */
@Component
class OidcDiscoveryCache {

    private record Entry(String issuer, OidcDiscoveryClient.Metadata metadata, Instant expiresAt) {
    }

    private final OidcDiscoveryClient client;
    private final Clock clock;
    private final Duration ttl;
    private volatile Entry entry;

    @Autowired
    OidcDiscoveryCache(OidcDiscoveryClient client, Clock clock, OidcProperties properties) {
        this(client, clock, properties.discoveryCacheTtl());
    }

    OidcDiscoveryCache(OidcDiscoveryClient client, Clock clock, Duration ttl) {
        this.client = client;
        this.clock = clock;
        this.ttl = ttl;
    }

    OidcDiscoveryClient.Metadata get(String issuer) {
        Entry cached = entry;
        Instant now = clock.instant();
        if (cached != null && cached.issuer().equals(issuer) && now.isBefore(cached.expiresAt())) {
            return cached.metadata();
        }
        OidcDiscoveryClient.Metadata fresh = client.fetch(issuer);
        entry = new Entry(issuer, fresh, now.plus(ttl));
        return fresh;
    }

    void invalidate() {
        entry = null;
    }
}
