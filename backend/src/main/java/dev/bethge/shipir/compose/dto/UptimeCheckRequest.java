package dev.bethge.shipir.compose.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code expectedStatus} null = any 2xx. The URL must be http or https with a host and no credentials; further checks
 * happen in the service.
 */
public record UptimeCheckRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 2048) String url,
                                 @Min(100) @Max(599) Integer expectedStatus,
                                 @NotNull @Min(30) @Max(86400) Integer intervalSeconds,
                                 @NotNull @Min(1) @Max(30) Integer timeoutSeconds, Boolean enabled) {
}
