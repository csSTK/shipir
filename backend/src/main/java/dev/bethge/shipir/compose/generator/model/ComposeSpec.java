package dev.bethge.shipir.compose.generator.model;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Wizard input: the whole stack. Networks and volumes referenced by services must be declared here. */
public record ComposeSpec(
        @NotEmpty List<ServiceSpec> services,
        List<NetworkSpec> networks,
        List<VolumeSpec> volumes) {
}
