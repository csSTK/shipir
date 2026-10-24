package dev.bethge.shipir.docker.dto;

import java.util.List;
import java.util.Map;

/**
 * What to create: already validated by the caller. {@code restartPolicy} is one of no, always, unless-stopped,
 * on-failure (null = Docker default). {@code network} is a network name (null = default bridge).
 */
public record ContainerCreateSpec(
        String name,
        String image,
        List<String> command,
        Map<String, String> env,
        List<Port> ports,
        List<Mount> mounts,
        String network,
        String restartPolicy,
        boolean privileged,
        boolean hostNetwork,
        boolean start) {

    /** {@code hostPort} null = Docker picks a free port; {@code protocol} is tcp or udp. */
    public record Port(Integer hostPort, Integer containerPort, String protocol) {
    }

    /** {@code type} BIND (source = absolute host path) or VOLUME (source = volume name). */
    public record Mount(String type, String source, String target, Boolean readOnly) {
    }
}
