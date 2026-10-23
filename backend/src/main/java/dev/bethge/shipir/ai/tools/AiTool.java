package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.ai.llm.LlmToolDefinition;
import dev.bethge.shipir.auth.Role;

import java.util.Map;

/**
 * One capability offered to the model. {@code parameters} is a JSON Schema object (validated by the registry
 * before {@link #execute}). {@code write} marks tools that change state; those are gated by user confirmation
 * (shipr-9sm.6). All tools of this package are read-only.
 */
public interface AiTool {

    String name();

    String description();

    Map<String, Object> parameters();

    /** Least privileged role that may use the tool (ADMIN &gt; OPERATOR &gt; VIEWER). */
    Role requiredRole();

    boolean write();

    ToolResult execute(Map<String, Object> args, ToolContext ctx);

    default boolean allowedFor(Role role) {
        return role != null && role.ordinal() <= requiredRole().ordinal();
    }

    default LlmToolDefinition definition() {
        return new LlmToolDefinition(name(), description(), parameters());
    }
}
