package dev.bethge.shipir.auth.oidc;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** The public authorize endpoint reuses the discovery document and is rate limited per client address. */
@TestPropertySource(properties = {"shipir.security.login.max-attempts=2", "shipir.oidc.discovery-cache-ttl=10m"})
class OidcAuthorizeCacheAndThrottleTest extends OidcTestBase {

    @Autowired
    OidcDiscoveryCache discoveryCache;

    private String authorizeLocation(String address) throws Exception {
        MvcResult r = mvc.perform(get("/api/auth/oidc/authorize").with(req -> {
            req.setRemoteAddr(address);
            return req;
        })).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        return r.getResponse().getHeader("Location");
    }

    @Test
    void discoveryIsFetchedOnceForRepeatedAuthorizeCalls() throws Exception {
        configure(false, true);
        discoveryCache.invalidate();
        int before = provider.discoveryHits();

        assertThat(authorizeLocation("10.1.0.1")).startsWith(provider.issuer() + "/authorize");
        assertThat(authorizeLocation("10.1.0.2")).startsWith(provider.issuer() + "/authorize");

        assertThat(provider.discoveryHits() - before).isEqualTo(1);
        // a provider outage does not affect sign-ins that start from the cached document
        provider.breakDiscovery();
        assertThat(authorizeLocation("10.1.0.3")).startsWith(provider.issuer() + "/authorize");
    }

    @Test
    void savingTheSettingsDropsTheCachedDiscoveryDocument() throws Exception {
        configure(false, true);
        authorizeLocation("10.2.0.1");
        provider.breakDiscovery();

        configure(false, true);

        assertThat(authorizeLocation("10.2.0.1")).isEqualTo("/?oidc_error=PROVIDER_UNREACHABLE");
    }

    @Test
    void authorizeIsRefusedAfterTheAddressLimitAndOtherAddressesAreUnaffected() throws Exception {
        configure(false, true);
        // max-attempts=2 -> 8 per address
        for (int i = 0; i < 8; i++) {
            assertThat(authorizeLocation("10.3.0.1")).startsWith(provider.issuer() + "/authorize");
        }

        assertThat(authorizeLocation("10.3.0.1")).isEqualTo("/?oidc_error=TOO_MANY_ATTEMPTS");
        assertThat(authorizeLocation("10.3.0.2")).startsWith(provider.issuer() + "/authorize");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'LOGIN_THROTTLED'", Integer.class)).isEqualTo(1);
    }
}
