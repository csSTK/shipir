package dev.bethge.shipir.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientAddressResolverTest {

    private static ClientAddressResolver resolver(String... trusted) {
        return new ClientAddressResolver(new AuthThrottleProperties(null, List.of(trusted)));
    }

    private static MockHttpServletRequest request(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    void twoClientsBehindProxyInTrustedCidrGetSeparateAddresses() {
        ClientAddressResolver resolver = resolver("172.28.77.0/24");

        assertThat(resolver.resolve(request("172.28.77.3", "203.0.113.10"))).isEqualTo("203.0.113.10");
        assertThat(resolver.resolve(request("172.28.77.3", "203.0.113.11"))).isEqualTo("203.0.113.11");
    }

    @Test
    void forwardedForFromPeerOutsideTrustedCidrIsIgnored() {
        ClientAddressResolver resolver = resolver("172.28.77.0/24");

        assertThat(resolver.resolve(request("172.28.78.3", "203.0.113.10"))).isEqualTo("172.28.78.3");
        assertThat(resolver.resolve(request("198.51.100.5", "10.0.0.1"))).isEqualTo("198.51.100.5");
    }

    @Test
    void trustedHopsInChainAreSkippedAndSpoofedPrefixIgnored() {
        ClientAddressResolver resolver = resolver("172.28.77.0/24", "192.0.2.1");

        assertThat(resolver.resolve(request("172.28.77.3", "6.6.6.6, 203.0.113.10, 192.0.2.1, 172.28.77.9")))
                .isEqualTo("203.0.113.10");
    }

    @Test
    void exactAddressStillWorksAndEmptyListTrustsNobody() {
        assertThat(resolver("172.28.77.3").resolve(request("172.28.77.3", "203.0.113.10"))).isEqualTo("203.0.113.10");
        assertThat(resolver().resolve(request("172.28.77.3", "203.0.113.10"))).isEqualTo("172.28.77.3");
    }

    @Test
    void ipv6CidrAndMappedIpv4Match() {
        assertThat(resolver("fd00:1::/64").resolve(request("fd00:1::5", "203.0.113.10"))).isEqualTo("203.0.113.10");
        assertThat(resolver("fd00:1::/64").resolve(request("fd00:2::5", "203.0.113.10"))).isEqualTo("fd00:2::5");
        assertThat(resolver("172.28.77.0/24").resolve(request("::ffff:172.28.77.3", "203.0.113.10")))
                .isEqualTo("203.0.113.10");
    }

    @Test
    void garbageHopsAreNeverResolvedAsHostnamesOrTrusted() {
        ClientAddressResolver resolver = resolver("172.28.77.0/24");

        assertThat(resolver.resolve(request("172.28.77.3", "evil.example.com"))).isEqualTo("evil.example.com");
        assertThat(resolver.resolve(request("172.28.77.3", "203.0.113.10, not-an-ip"))).isEqualTo("not-an-ip");
    }

    @Test
    void hexLookingHostnamesAndShortIpv4AreNotTrustedLiterals() {
        ClientAddressResolver narrow = resolver("172.28.0.0/24");

        for (String hop : new String[]{"abc", "dead.beef", "172.28.77", "1.2.3", "1.2.3.4.5", "256.1.1.1"}) {
            assertThat(narrow.resolve(request("172.28.0.3", "203.0.113.10, " + hop))).isEqualTo(hop);
        }
    }

    @Test
    void shortIpv4IsRejectedAsConfiguration() {
        assertThatThrownBy(() -> resolver("172.28.77")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver("abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidConfigurationFailsAtStartup() {
        assertThatThrownBy(() -> resolver("172.28.77.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver("proxy.example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver("10.0.0.0/abc")).isInstanceOf(IllegalArgumentException.class);
    }
}
