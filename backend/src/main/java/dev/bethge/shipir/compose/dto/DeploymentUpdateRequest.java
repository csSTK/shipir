package dev.bethge.shipir.compose.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Save-draft body. {@code env} null keeps the stored variables, an empty list clears them. {@code yaml} is required
 * for FILE deployments; for GIT deployments it may be omitted or must equal the current YAML (otherwise 400 GIT_MANAGED).
 */
public record DeploymentUpdateRequest(
        @Size(max = 500_000) String yaml,
        @Size(max = 500) List<@Valid EnvEntry> env) {

    @Override
    public String toString() {
        return "DeploymentUpdateRequest[]";
    }
}
