package dev.bethge.shipir.ai.chat;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * What the user is looking at in the UI when they send a chat message. Sent by the browser, so it is untrusted:
 * ids are format-checked here (a malformed or oversized value is a 400, so no free text can reach the prompt) and
 * the orchestrator keeps only the ids the user may view. Never stored with the messages and gives no tool any
 * extra right; it only tells the model what "this host" means.
 */
public record ViewContext(
        @NotNull Page page,
        @Pattern(regexp = UUID_PATTERN) String hostId,
        @Pattern(regexp = UUID_PATTERN) String deploymentId,
        @Pattern(regexp = CONTAINER_ID_PATTERN) String containerId) {

    static final String UUID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    /** Docker container id (short 12 or full 64 hex characters, or anything between). */
    static final String CONTAINER_ID_PATTERN = "^[0-9a-fA-F]{12,64}$";

    public enum Page {
        OVERVIEW, HOST, DEPLOYMENT, CONTAINER, IMAGES, NETWORKS, VOLUMES, SETTINGS, OTHER
    }
}
