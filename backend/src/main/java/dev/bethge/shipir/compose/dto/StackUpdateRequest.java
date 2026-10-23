package dev.bethge.shipir.compose.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Save-draft body. {@code env} null keeps the stored variables, an empty list clears them. */
public record StackUpdateRequest(
        @NotBlank @Size(max = 500_000) String yaml,
        @Size(max = 500) List<@Valid EnvEntry> env) {

    @Override
    public String toString() {
        return "StackUpdateRequest[]";
    }
}
