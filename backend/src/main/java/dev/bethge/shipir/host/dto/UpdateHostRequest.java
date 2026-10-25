package dev.bethge.shipir.host.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Renaming, optionally replacing the tags in the same call. {@code tags} absent or null keeps the current tags.
 * Changing the connection means creating a new environment.
 */
public record UpdateHostRequest(@NotBlank @Size(max = 100) String name, List<String> tags) {
}
