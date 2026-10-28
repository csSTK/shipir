package dev.bethge.shipir.host;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.host.dto.ProxyDetectionResponse;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Finds a label based reverse proxy among the containers of a host: a running container whose image is
 * {@code traefik} or {@code caddy-docker-proxy} (registry prefix, tag and digest ignored; plain {@code caddy} is not
 * label driven). Traefik wins over Caddy when both run.
 * Network rule: of the proxy's user-defined networks the alphabetically first name is reported (deterministic; built-in
 * {@code bridge}/{@code host}/{@code none} are never reported).
 */
final class ProxyDetector {

    private static final Set<String> BUILT_IN_NETWORKS = Set.of("bridge", "host", "none");

    private ProxyDetector() {
    }

    static ProxyDetectionResponse detect(List<ContainerDto> containers,
                                         Function<String, ContainerDetailsDto> inspect) {
        for (ProxyType type : ProxyType.values()) {
            Optional<ContainerDto> found = containers.stream()
                    .filter(c -> "running".equals(c.state()) && type == typeOf(c.image()))
                    .findFirst();
            if (found.isPresent()) {
                ContainerDto c = found.get();
                return new ProxyDetectionResponse(true, type, c.name(), networkOf(inspect.apply(c.id())));
            }
        }
        return ProxyDetectionResponse.none();
    }

    private static ProxyType typeOf(String image) {
        if (image == null) {
            return null;
        }
        String name = image;
        int digest = name.indexOf('@');
        if (digest >= 0) {
            name = name.substring(0, digest);
        }
        name = name.substring(name.lastIndexOf('/') + 1);
        int tag = name.indexOf(':');
        if (tag >= 0) {
            name = name.substring(0, tag);
        }
        return switch (name) {
            case "traefik" -> ProxyType.TRAEFIK;
            case "caddy-docker-proxy" -> ProxyType.CADDY;
            default -> null;
        };
    }

    private static String networkOf(ContainerDetailsDto details) {
        return details.networks().stream()
                .map(ContainerDetailsDto.Network::name)
                .filter(n -> !BUILT_IN_NETWORKS.contains(n))
                .min(Comparator.naturalOrder())
                .orElse(null);
    }
}
