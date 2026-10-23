package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.auth.Role;

import java.util.UUID;

/** The user a tool call runs for; tools never act with more rights than this user has. */
public record ToolContext(UUID userId, String username, Role role) {
}
