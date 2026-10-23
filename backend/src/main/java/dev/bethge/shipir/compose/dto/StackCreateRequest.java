package dev.bethge.shipir.compose.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Create body. {@code toString} omits YAML and env. */
public record StackCreateRequest(
        @NotBlank @Size(max = 63) String name,
        @NotBlank @Size(max = 500_000) String yaml,
        @Size(max = 500) List<@Valid EnvEntry> env) {

    @Override
    public String toString() {
        return "StackCreateRequest[name=" + name + "]";
    }
}
