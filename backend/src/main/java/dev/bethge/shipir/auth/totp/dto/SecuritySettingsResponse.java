package dev.bethge.shipir.auth.totp.dto;

import java.time.Instant;

public record SecuritySettingsResponse(boolean require2faForAdmins, Instant updatedAt) {
}
