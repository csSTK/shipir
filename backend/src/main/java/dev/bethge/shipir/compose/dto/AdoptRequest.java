package dev.bethge.shipir.compose.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Adopt body. Both fields are optional: without {@code yaml} the compose file is read from the paths the project's
 * containers were started with (LOCAL host and allow-listed directories only); {@code env} null reads a {@code .env}
 * next to it when present. {@code toString} omits YAML and env.
 */
public record AdoptRequest(
        @Size(max = 500_000) String yaml,
        @Size(max = 500) List<@Valid EnvEntry> env) {

    @Override
    public String toString() {
        return "AdoptRequest[yaml=" + (yaml == null ? "null" : "<hidden>") + ", env=" + (env == null ? "null" : "<hidden>") + "]";
    }
}
