package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.auth.Role;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/** {@link AiTool} backed by a function whose return value is serialised as the result. */
record FunctionTool(String name, String description, Map<String, Object> parameters, Role requiredRole,
                    boolean write, BiFunction<Map<String, Object>, ToolContext, Object> handler,
                    Function<Object, String> serializer) implements AiTool {

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        return ToolResult.ok(serializer.apply(handler.apply(args, ctx)));
    }
}
