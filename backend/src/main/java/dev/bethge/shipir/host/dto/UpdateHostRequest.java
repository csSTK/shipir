package dev.bethge.shipir.host.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Only renaming is supported; changing the connection means creating a new environment. */
public record UpdateHostRequest(@NotBlank @Size(max = 100) String name) {
}
