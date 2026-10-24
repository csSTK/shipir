package dev.bethge.shipir.docker.dto;

/** A resource the daemon just created: its id and (for volumes: the same as the id) name. */
public record CreatedResourceDto(String id, String name) {
}
