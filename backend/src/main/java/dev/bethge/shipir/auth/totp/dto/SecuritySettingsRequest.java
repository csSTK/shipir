package dev.bethge.shipir.auth.totp.dto;

import jakarta.validation.constraints.NotNull;

public record SecuritySettingsRequest(@NotNull Boolean require2faForAdmins) {
}
