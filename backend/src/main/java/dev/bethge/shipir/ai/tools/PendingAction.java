package dev.bethge.shipir.ai.tools;

import java.time.Instant;
import java.util.Map;

/**
 * A write tool call waiting for the user's yes. Shown to the user to confirm; the {@code token} is the only way to
 * run it ({@link AiToolRegistry#executeConfirmed}). The registry keeps the real arguments; {@code arguments} here is
 * for display only, with {@code env} values masked.
 */
public record PendingAction(String token, String toolName, String description, Map<String, Object> arguments,
                            Instant expiresAt) {
}
