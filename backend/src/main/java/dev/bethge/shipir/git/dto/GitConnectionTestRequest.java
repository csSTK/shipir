package dev.bethge.shipir.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GitConnectionTestRequest(@NotBlank @Size(max = 500) String repoUrl) {
}
