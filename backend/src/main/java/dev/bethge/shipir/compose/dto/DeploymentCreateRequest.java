package dev.bethge.shipir.compose.dto;

import dev.bethge.shipir.compose.DeploymentSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

/**
 * Create body. {@code source} defaults to FILE, which requires {@code yaml}; GIT requires {@code repoUrl}, {@code ref}
 * and {@code composePath} (credential optional) and ignores {@code yaml}. Required-field checks happen in the service.
 * {@code toString} omits YAML and env.
 */
public record DeploymentCreateRequest(
        @NotBlank @Size(max = 63) String name,
        @Size(max = 500_000) String yaml,
        @Size(max = 500) List<@Valid EnvEntry> env,
        DeploymentSource source,
        @Size(max = 500) String repoUrl,
        @Size(max = 255) String ref,
        @Size(max = 500) String composePath,
        UUID credentialId) {

    /** FILE deployment with the given YAML. */
    public DeploymentCreateRequest(String name, String yaml, List<EnvEntry> env) {
        this(name, yaml, env, DeploymentSource.FILE, null, null, null, null);
    }

    @Override
    public String toString() {
        return "DeploymentCreateRequest[name=" + name + "]";
    }
}
