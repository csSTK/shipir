package dev.bethge.shipir.auth.totp.dto;

import java.util.List;

/** Shown once, right after confirming; only hashes are stored. */
public record RecoveryCodesResponse(List<String> recoveryCodes) {

    @Override
    public String toString() {
        return "RecoveryCodesResponse[***]";
    }
}
