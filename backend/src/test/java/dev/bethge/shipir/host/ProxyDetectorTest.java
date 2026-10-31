package dev.bethge.shipir.host;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.host.dto.ProxyDetectionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyDetectorTest {

    private static ContainerDto container(String id, String image, String state) {
        return new ContainerDto(id, "name-" + id, image, state, "", null, null, List.of(), null, null);
    }

    private static Function<String, ContainerDetailsDto> networks(Map<String, List<String>> byId) {
        return id -> new ContainerDetailsDto(id, "name-" + id, "img", "running", null, null, 0, null, List.of(),
                List.of(), false, false, List.of(), List.of(), Map.of(),
                byId.getOrDefault(id, List.of()).stream()
                        .map(n -> new ContainerDetailsDto.Network(n, null, null, null, List.of())).toList(),
                List.of(), null, null, null);
    }

    private static Function<String, ContainerDetailsDto> withCommand(List<String> networks, List<String> command) {
        Function<String, ContainerDetailsDto> base = networks(Map.of("t", networks));
        return id -> {
            ContainerDetailsDto d = base.apply(id);
            return new ContainerDetailsDto(d.id(), d.name(), d.image(), d.state(), null, null, 0, null, command,
                    List.of(), false, false, List.of(), List.of(), Map.of(), d.networks(), List.of(), null, null, null);
        };
    }

    private static String networkFor(String image, List<String> networks, List<String> command) {
        return ProxyDetector.detect(List.of(container("t", image, "running")), withCommand(networks, command))
                .network();
    }

    @Test
    void traefikProviderNetworkArgBeatsAlphabeticalOrder() {
        assertThat(networkFor("traefik:v3", List.of("alpha", "web"),
                List.of("--providers.docker=true", "--providers.docker.network=web"))).isEqualTo("web");
    }

    @Test
    void traefikProviderNetworkArgWithSpaceSeparatedValue() {
        assertThat(networkFor("traefik:v3", List.of("alpha", "web"),
                List.of("--providers.docker.network", "web"))).isEqualTo("web");
    }

    @Test
    void providerNetworkArgNotAttachedToProxyFallsBackToAlphabetical() {
        assertThat(networkFor("traefik:v3", List.of("alpha", "web"),
                List.of("--providers.docker.network=gone"))).isEqualTo("alpha");
    }

    @Test
    void builtInProviderNetworkArgIsIgnored() {
        assertThat(networkFor("traefik:v3", List.of("bridge", "web"),
                List.of("--providers.docker.network=bridge"))).isEqualTo("web");
    }

    @Test
    void noProviderNetworkArgFallsBackToAlphabetical() {
        assertThat(networkFor("traefik:v3", List.of("web", "alpha"), List.of("--api.insecure=true")))
                .isEqualTo("alpha");
    }

    @Test
    void detectsRunningTraefikAndItsUserDefinedNetwork() {
        ProxyDetectionResponse r = ProxyDetector.detect(
                List.of(container("a", "nginx:1", "running"), container("t", "traefik:v3.1", "running")),
                networks(Map.of("t", List.of("bridge", "proxy"))));
        assertThat(r).isEqualTo(new ProxyDetectionResponse(true, ProxyType.TRAEFIK, "name-t", "proxy"));
    }

    @Test
    void detectsCaddyDockerProxy() {
        ProxyDetectionResponse r = ProxyDetector.detect(
                List.of(container("c", "lucaslorentz/caddy-docker-proxy:2.9-alpine", "running")),
                networks(Map.of("c", List.of("caddy"))));
        assertThat(r).isEqualTo(new ProxyDetectionResponse(true, ProxyType.CADDY, "name-c", "caddy"));
    }

    @ParameterizedTest
    @CsvSource({
            "traefik, TRAEFIK",
            "traefik@sha256:abcdef, TRAEFIK",
            "docker.io/library/traefik:latest, TRAEFIK",
            "registry.example.com:5000/infra/traefik:v2, TRAEFIK",
            "ghcr.io/lucaslorentz/caddy-docker-proxy:ci-alpine, CADDY",
    })
    void recognisesImageReferenceForms(String image, ProxyType expected) {
        assertThat(ProxyDetector.detect(List.of(container("x", image, "running")), networks(Map.of())).proxy())
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"caddy:2", "my-traefik-fork:1", "traefik-whoami:1", "traefik/whoami", "nginx"})
    void ignoresOtherImages(String image) {
        assertThat(ProxyDetector.detect(List.of(container("x", image, "running")), networks(Map.of())))
                .isEqualTo(ProxyDetectionResponse.none());
    }

    @Test
    void ignoresStoppedProxy() {
        assertThat(ProxyDetector.detect(List.of(container("t", "traefik:v3", "exited")), networks(Map.of())))
                .isEqualTo(ProxyDetectionResponse.none());
    }

    @Test
    void networkIsNullWhenOnlyBuiltInNetworks() {
        ProxyDetectionResponse r = ProxyDetector.detect(List.of(container("t", "traefik:v3", "running")),
                networks(Map.of("t", List.of("bridge", "host"))));
        assertThat(r.proxy()).isEqualTo(ProxyType.TRAEFIK);
        assertThat(r.network()).isNull();
    }

    @Test
    void picksFirstUserNetworkAlphabeticallyAndPrefersTraefikOverCaddy() {
        ProxyDetectionResponse r = ProxyDetector.detect(
                List.of(container("c", "lucaslorentz/caddy-docker-proxy:2", "running"),
                        container("t", "traefik:v3", "running")),
                networks(Map.of("t", List.of("zeta", "alpha"), "c", List.of("caddy"))));
        assertThat(r).isEqualTo(new ProxyDetectionResponse(true, ProxyType.TRAEFIK, "name-t", "alpha"));
    }

    /** Documented rule: of several user-defined networks the alphabetically first wins, built-in ones never. */
    @Test
    void severalUserNetworksPickTheAlphabeticallyFirstRegardlessOfOrder() {
        ProxyDetectionResponse r = ProxyDetector.detect(List.of(container("t", "traefik:v3", "running")),
                networks(Map.of("t", List.of("bridge", "web", "monitoring", "edge", "host"))));
        assertThat(r.network()).isEqualTo("edge");
    }

    @Test
    void noProxyOnAFullyVisibleHostIsVisible() {
        assertThat(ProxyDetector.detect(List.of(), networks(Map.of())).visible()).isTrue();
    }

    @Test
    void notVisibleIsDistinctFromNoProxy() {
        assertThat(ProxyDetectionResponse.notVisible().visible()).isFalse();
        assertThat(ProxyDetectionResponse.notVisible()).isNotEqualTo(ProxyDetectionResponse.none());
    }

    @Test
    void emptyHostHasNoProxy() {
        assertThat(ProxyDetector.detect(List.of(), networks(Map.of()))).isEqualTo(ProxyDetectionResponse.none());
    }
}
