package dev.bethge.shipir.host;

import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The registry named in an image reference is a connection target of the daemon and goes through the egress policy. */
class ImageReferenceRegistryEgressTest {

    private static EgressPolicy policy(boolean allowPrivate) {
        return new EgressPolicy(new EgressProperties(allowPrivate), host -> switch (host) {
            case "registry.example.com", "ghcr.io" -> new InetAddress[]{InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34})};
            case "internal.example.com" -> new InetAddress[]{InetAddress.getByAddress(new byte[]{10, 0, 0, 5})};
            case "localhost" -> new InetAddress[]{InetAddress.getLoopbackAddress()};
            case "10.0.0.5" -> new InetAddress[]{InetAddress.getByAddress(new byte[]{10, 0, 0, 5})};
            default -> throw new UnknownHostException(host);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.5:5000/x", "10.0.0.5:5000/x:v1", "internal.example.com/team/app:1",
            "localhost:5000/app", "localhost/app", "169.254.169.254/x", "metadata.google.internal/x",
            "unresolvable.example.org/x"})
    void refusesRegistriesTheEgressPolicyDenies(String reference) {
        assertThatThrownBy(() -> ImageReferenceRules.requireAllowedRegistry(policy(false), reference))
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("REGISTRY_NOT_ALLOWED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"registry.example.com/team/app:1", "registry.example.com:5443/app", "ghcr.io/o/app@sha256:"
            + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"})
    void allowsPublicRegistries(String reference) {
        assertThatCode(() -> ImageReferenceRules.requireAllowedRegistry(policy(false), reference)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nginx", "nginx:1.27", "library/nginx", "docker.io/library/nginx", "myorg/app:v2"})
    void dockerHubReferencesAreNotResolved(String reference) {
        // the resolver of the policy knows no Hub host: a lookup would be denied
        assertThatCode(() -> ImageReferenceRules.requireAllowedRegistry(policy(false), reference)).doesNotThrowAnyException();
    }

    @Test
    void privateRegistryIsAllowedWhenPrivateRangesAreAllowed() {
        assertThatCode(() -> ImageReferenceRules.requireAllowedRegistry(policy(true), "10.0.0.5:5000/x")).doesNotThrowAnyException();
    }

    @Test
    void metadataAddressesStayDeniedEvenWhenPrivateRangesAreAllowed() {
        assertThatThrownBy(() -> ImageReferenceRules.requireAllowedRegistry(policy(true), "169.254.169.254/x"))
                .isInstanceOf(HostException.class);
    }
}
