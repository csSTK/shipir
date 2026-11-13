package dev.bethge.shipir.compose.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Confirms a reviewed pull (step 2 of "Pull &amp; redeploy"): the deploy only runs while the checkout still hashes to
 * {@code graphHash}. {@code graphHash} is null when step 1 returned none (unhashable graph).
 */
public record GitRedeployRequest(
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{40}", message = "must be a full commit SHA") String commitSha,
        String graphHash) {
}
