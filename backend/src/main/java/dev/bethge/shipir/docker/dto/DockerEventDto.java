package dev.bethge.shipir.docker.dto;

import java.time.Instant;

/**
 * One Docker daemon event about a container, image, network or volume. {@code action} is Docker's action
 * (start, die, create, destroy, pull, ...), {@code id} the object id, {@code name} its name attribute when
 * Docker sends one, {@code composeProject} the {@code com.docker.compose.project} label (containers,
 * networks, volumes created by Compose), both null when absent.
 */
public record DockerEventDto(Type type, String action, String id, String name, String composeProject, Instant time) {

    public enum Type { CONTAINER, IMAGE, NETWORK, VOLUME }
}
