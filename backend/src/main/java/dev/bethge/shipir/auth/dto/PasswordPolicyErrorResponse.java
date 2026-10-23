package dev.bethge.shipir.auth.dto;

import java.util.List;

/**
 * 400 body for a rejected new password: {@code code} PASSWORD_POLICY plus the violated rule codes in a
 * top-level {@code violations} array. Dedicated record instead of widening the generic
 * {@code ErrorResponse} with a field only this endpoint uses.
 */
public record PasswordPolicyErrorResponse(String code, String message, List<String> violations) {

    public static PasswordPolicyErrorResponse of(List<String> violations) {
        return new PasswordPolicyErrorResponse("PASSWORD_POLICY",
                "The new password does not meet the password policy", violations);
    }
}
