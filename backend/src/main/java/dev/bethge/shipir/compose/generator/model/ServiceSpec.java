package dev.bethge.shipir.compose.generator.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * One Compose service. {@code command} is exec form (one element per argument). {@code restart} is one of
 * no, always, on-failure, unless-stopped (default none = omitted). {@code domain} adds reverse proxy labels (and the proxy network).
 */
public record ServiceSpec(
        @NotBlank String name,
        @NotBlank String image,
        List<String> command,
        String restart,
        List<PortSpec> ports,
        List<EnvVarSpec> environment,
        List<VolumeMountSpec> volumes,
        List<String> networks,
        Map<String, String> labels,
        List<String> dependsOn,
        DomainSpec domain) {

    /** Service without a domain. */
    public ServiceSpec(String name, String image, List<String> command, String restart, List<PortSpec> ports,
                       List<EnvVarSpec> environment, List<VolumeMountSpec> volumes, List<String> networks,
                       Map<String, String> labels, List<String> dependsOn) {
        this(name, image, command, restart, ports, environment, volumes, networks, labels, dependsOn, null);
    }
}
