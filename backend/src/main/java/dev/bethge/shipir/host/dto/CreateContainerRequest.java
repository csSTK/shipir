package dev.bethge.shipir.host.dto;

import dev.bethge.shipir.docker.dto.ContainerCreateSpec;

import java.util.List;
import java.util.Map;

/**
 * Create a container. Only {@code image} is required; null lists/maps/booleans mean empty/false. {@code restartPolicy}:
 * no, always, unless-stopped, on-failure. {@code ports[].hostPort} null = random free port, {@code protocol} tcp (default)
 * or udp; {@code mounts[].type} VOLUME (source = volume name) or BIND (source = absolute host path, ADMIN only).
 * {@code networks[]} (name, optional aliases) are all attached; empty = default bridge. {@code privileged}, {@code hostNetwork} and bind mounts are ADMIN only. {@code start} true starts it right away.
 */
public record CreateContainerRequest(
        String name,
        String image,
        List<String> command,
        Map<String, String> env,
        List<ContainerCreateSpec.Port> ports,
        List<ContainerCreateSpec.Mount> mounts,
        List<ContainerCreateSpec.Network> networks,
        String restartPolicy,
        Boolean privileged,
        Boolean hostNetwork,
        Boolean start) {

    /** Env values may be secrets: never part of toString. */
    @Override
    public String toString() {
        return "CreateContainerRequest[name=" + name + ", image=" + image + "]";
    }
}
