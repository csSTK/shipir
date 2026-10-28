package dev.bethge.shipir.common.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EgressPolicyTest {

    private static EgressPolicy deny() {
        return new EgressPolicy(new EgressProperties(false));
    }

    private static EgressPolicy allowPrivate() {
        return new EgressPolicy(new EgressProperties(true));
    }

    private static boolean allowed(EgressPolicy policy, String literal) throws Exception {
        return policy.isAllowed(InetAddress.getByName(literal));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.1.2.3", "::1", "0.0.0.0", "::", "169.254.169.254", "169.254.0.1",
            "fe80::1", "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.1", "100.64.0.1", "100.100.100.200",
            "fc00::1", "fd12:3456::1", "fd00:ec2::254", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254",
            "64:ff9b::7f00:1", "224.0.0.1", "255.255.255.255", "240.0.0.1"})
    void deniesInternalAndSpecialAddressesByDefault(String literal) throws Exception {
        assertThat(allowed(deny(), literal)).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.1", "0.1.2.3", "0.255.255.255", "192.0.0.0", "192.0.0.1", "192.0.0.255",
            "198.18.0.0", "198.18.5.5", "198.19.255.255", "::ffff:0.1.2.3", "64:ff9b::c612:101"})
    void deniesZeroNetworkIetfProtocolAndBenchmarkRanges(String literal) throws Exception {
        assertThat(allowed(deny(), literal)).as(literal).isFalse();
        assertThat(allowed(allowPrivate(), literal)).as(literal + " with opt-in").isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0.0.1", "192.0.1.1", "192.0.2.1", "198.17.255.255", "198.20.0.0"})
    void allowsAddressesJustOutsideSpecialUseRanges(String literal) throws Exception {
        assertThat(allowed(deny(), literal)).as(literal).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "172.32.0.1", "2606:4700:4700::1111", "::ffff:8.8.8.8"})
    void allowsPublicAddresses(String literal) throws Exception {
        assertThat(allowed(deny(), literal)).as(literal).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "10.0.0.5", "192.168.1.1", "fd12:3456::1", "::ffff:10.0.0.1"})
    void optInAllowsPrivateAndLoopback(String literal) throws Exception {
        assertThat(allowed(allowPrivate(), literal)).as(literal).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"169.254.169.254", "169.254.1.1", "fe80::1", "0.0.0.0", "fd00:ec2::254", "100.100.100.200",
            "::ffff:169.254.169.254", "224.0.0.1", "255.255.255.255"})
    void optInNeverUnlocksLinkLocalMetadataOrUnspecified(String literal) throws Exception {
        assertThat(allowed(allowPrivate(), literal)).as(literal).isFalse();
    }

    @Test
    void resolveRejectsHostWhenAnyAnswerIsInternal() {
        EgressPolicy policy = new EgressPolicy(new EgressProperties(false), host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")});
        assertThatThrownBy(() -> policy.resolve("mixed.test")).isInstanceOf(EgressDeniedException.class);
    }

    @Test
    void resolveReturnsTheValidatedAddresses() throws Exception {
        InetAddress pub = InetAddress.getByName("93.184.216.34");
        EgressPolicy policy = new EgressPolicy(new EgressProperties(false), host -> new InetAddress[]{pub});
        assertThat(policy.resolve("public.test")).containsExactly(pub);
    }

    @ParameterizedTest
    @ValueSource(strings = {"metadata.google.internal", "METADATA.GOOGLE.INTERNAL.", "metadata", "instance-data"})
    void metadataHostnamesAreBlockedWithoutResolvingEvenWithOptIn(String host) {
        AtomicInteger lookups = new AtomicInteger();
        EgressPolicy policy = new EgressPolicy(new EgressProperties(true), h -> {
            lookups.incrementAndGet();
            return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
        });
        assertThatThrownBy(() -> policy.resolve(host)).isInstanceOf(EgressDeniedException.class);
        assertThat(lookups).hasValue(0);
    }

    @Test
    void literalAddressesInUrisAreChecked() {
        EgressPolicy policy = deny();
        assertThatThrownBy(() -> policy.check(java.net.URI.create("https://127.0.0.1:8443/x.git")))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> policy.check(java.net.URI.create("https://[::ffff:10.0.0.5]/x.git")))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> policy.check(java.net.URI.create("https://169.254.169.254/latest")))
                .isInstanceOf(EgressDeniedException.class);
    }

    @Test
    void unresolvableAndDeniedHostsLookTheSame() {
        EgressPolicy policy = new EgressPolicy(new EgressProperties(false), host -> {
            if (host.equals("gone.test")) {
                throw new java.net.UnknownHostException(host);
            }
            return new InetAddress[]{InetAddress.getByName("10.0.0.5")};
        });
        Throwable unresolvable = org.assertj.core.api.Assertions.catchThrowable(() -> policy.resolve("gone.test"));
        Throwable denied = org.assertj.core.api.Assertions.catchThrowable(() -> policy.resolve("internal.test"));
        assertThat(unresolvable).isInstanceOf(EgressDeniedException.class);
        assertThat(denied).isInstanceOf(EgressDeniedException.class).hasMessage(unresolvable.getMessage());
    }
}
