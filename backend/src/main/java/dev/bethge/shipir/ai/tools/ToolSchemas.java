package dev.bethge.shipir.ai.tools;

import java.util.List;
import java.util.Map;

/**
 * JSON Schema fragments shared by {@link ReadToolsConfig} and {@link WriteToolsConfig}. Keeping them in one place means a
 * change to the shape of an id or of the argument envelope is made once, not once per config.
 */
final class ToolSchemas {

    /** A UUID argument, sent as a string. */
    static final Map<String, Object> UUID_PROP = Map.of("type", "string", "format", "uuid");

    private ToolSchemas() {
    }

    static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        // No additionalProperties: ArgumentValidator already rejects unknown arguments, and it costs tokens per tool.
        return required.length == 0 ? Map.of("type", "object", "properties", properties)
                : Map.of("type", "object", "properties", properties, "required", List.of(required));
    }

    /** Schema for a tool that takes only the environment id. */
    static Map<String, Object> hostSchema() {
        return schema(Map.of("hostId", UUID_PROP), "hostId");
    }

    /** Schema for a tool that takes the environment and deployment ids. */
    static Map<String, Object> deploymentSchema() {
        return schema(Map.of("hostId", UUID_PROP, "deploymentId", UUID_PROP), "hostId", "deploymentId");
    }
}
