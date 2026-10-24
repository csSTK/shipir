package dev.bethge.shipir.auth.totp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DisableRequest(@NotBlank @Size(max = 1024) String password, @NotBlank @Size(max = 64) String code) {

    @Override
    public String toString() {
        return "DisableRequest[***]";
    }
}
