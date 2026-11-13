package dev.bethge.shipir.auth.stepup;

/** {@code method}: PASSWORD (send password, plus code if {@code totpRequired}) or SSO (re-authenticate at the provider; {@code fresh} = done recently enough). */
public record StepUpStatusResponse(String method, boolean totpRequired, boolean fresh, long windowSeconds) {
}
