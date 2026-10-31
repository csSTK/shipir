package dev.bethge.shipir.docker.dto;

import java.util.List;
import java.util.Map;

/**
 * What to create: already validated by the caller. {@code restartPolicy} is one of no, always, unless-stopped,
 * on-failure (null = Docker default). {@code networks} are the networks to attach (empty = default bridge); the first one is the container's primary network.
 */
public record ContainerCreateSpec(
        String name,
        String image,
        List<String> command,
        Map<String, String> env,
        List<Port> ports,
        List<Mount> mounts,
        List<Network> networks,
        String restartPolicy,
        boolean privileged,
        boolean hostNetwork,
        boolean start) {

    /** A network by name with optional extra DNS aliases on it (empty = none). */
    public record Network(String name, List<String> aliases) {
    }

    /** {@code hostPort} null = Docker picks a free port; {@code protocol} is tcp or udp. */
    public record Port(Integer hostPort, Integer containerPort, String protocol) {
    }

    /** {@code type} BIND (source = absolute host path) or VOLUME (source = volume name). */
    public record Mount(String type, String source, String target, Boolean readOnly) {
    }
}
