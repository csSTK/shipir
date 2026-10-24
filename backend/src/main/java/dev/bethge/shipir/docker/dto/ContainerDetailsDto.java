package dev.bethge.shipir.docker.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Inspect view of one container. {@code env} values are filled by the docker/ layer; callers that must not
 * expose them replace them via {@link #withoutEnvValues()}. {@code health} is null without a healthcheck,
 * {@code startedAt} null if the container never ran, compose fields null for non-Compose containers.
 */
public record ContainerDetailsDto(
        String id,
        String name,
        String image,
        String state,
        Instant created,
        Instant startedAt,
        int restartCount,
        RestartPolicy restartPolicy,
        List<String> command,
        List<EnvVar> env,
        List<Mount> mounts,
        Map<String, String> labels,
        List<Network> networks,
        List<Port> ports,
        Health health,
        String composeProject,
        String composeService) {

    /** {@code name} is Docker's policy name ({@code no}, {@code always}, {@code on-failure}, {@code unless-stopped}). */
    public record RestartPolicy(String name, int maxRetries) {
    }

    /** {@code value} is null when withheld. */
    public record EnvVar(String key, String value) {
    }

    /** {@code type} is {@code volume} or {@code bind}; {@code name} only for volumes. */
    public record Mount(String type, String name, String source, String destination, boolean readOnly) {
    }

    public record Network(String name, String ipAddress, String gateway, String macAddress, List<String> aliases) {
    }

    /** {@code hostPort}/{@code hostIp} null for ports that are exposed but not published. */
    public record Port(int containerPort, String protocol, String hostIp, Integer hostPort) {
    }

    /** {@code recentLogs}: newest last, at most five. */
    public record Health(String status, int failingStreak, List<HealthLog> recentLogs) {
    }

    public record HealthLog(Instant start, Instant end, int exitCode, String output) {
    }

    public ContainerDetailsDto withoutEnvValues() {
        return new ContainerDetailsDto(id, name, image, state, created, startedAt, restartCount, restartPolicy,
                command, env.stream().map(e -> new EnvVar(e.key(), null)).toList(), mounts, labels, networks, ports,
                health, composeProject, composeService);
    }
}
