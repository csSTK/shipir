package dev.bethge.shipir.compose.dto;

import java.util.List;

/** Unmasked env values; only served to ADMIN and OPERATOR. {@code toString} hides them. */
public record DeploymentEnvResponse(List<EnvEntry> entries) {

    @Override
    public String toString() {
        return "DeploymentEnvResponse[" + entries.size() + " entries]";
    }
}
