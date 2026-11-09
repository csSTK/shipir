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
 * Network rule: a Traefik {@code --providers.docker.network} command argument wins if the proxy is attached to that
 * user-defined network, then the {@code TRAEFIK_PROVIDERS_DOCKER_NETWORK} env var (same condition). A
 * {@code providers.docker.network} in a static traefik.yml/toml is not read (it lives in a file inside the container);
 * such setups fall back to the next rule. Otherwise of the proxy's user-defined networks the alphabetically first name is reported
 * (deterministic; built-in {@code bridge}/{@code host}/{@code none} are never reported).
 */
final class ProxyDetector {

    private static final String PROVIDER_NETWORK_ARG = "--providers.docker.network";
    private static final String PROVIDER_NETWORK_ENV = "TRAEFIK_PROVIDERS_DOCKER_NETWORK";
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
        List<String> userNetworks = details.networks().stream()
                .map(ContainerDetailsDto.Network::name)
                .filter(n -> !BUILT_IN_NETWORKS.contains(n))
                .toList();
        String fromArg = providerNetworkArg(details.command());
        if (fromArg != null && userNetworks.contains(fromArg)) {
            return fromArg;
        }
        String fromEnv = providerNetworkEnv(details.env());
        if (fromEnv != null && userNetworks.contains(fromEnv)) {
            return fromEnv;
        }
        return userNetworks.stream().min(Comparator.naturalOrder()).orElse(null);
    }

    /** Value of the {@code TRAEFIK_PROVIDERS_DOCKER_NETWORK} env var, else null. */
    private static String providerNetworkEnv(List<ContainerDetailsDto.EnvVar> env) {
        if (env == null) {
            return null;
        }
        return env.stream()
                .filter(e -> e != null && PROVIDER_NETWORK_ENV.equals(e.key()) && e.value() != null
                        && !e.value().isBlank())
                .map(ContainerDetailsDto.EnvVar::value)
                .findFirst().orElse(null);
    }

    /** Value of {@code --providers.docker.network=x} or {@code --providers.docker.network x}, else null. */
    private static String providerNetworkArg(List<String> command) {
        if (command == null) {
            return null;
        }
        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            if (arg == null) {
                continue;
            }
            if (arg.startsWith(PROVIDER_NETWORK_ARG + "=")) {
                String value = arg.substring(PROVIDER_NETWORK_ARG.length() + 1);
                return value.isBlank() ? null : value;
            }
            if (arg.equals(PROVIDER_NETWORK_ARG) && i + 1 < command.size() && command.get(i + 1) != null
                    && !command.get(i + 1).isBlank()) {
                return command.get(i + 1);
            }
        }
        return null;
    }
}
