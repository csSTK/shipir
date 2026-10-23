package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.ai.llm.LlmToolDefinition;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.compose.StackException;
import dev.bethge.shipir.host.HostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Entry point for the chat feature: offers the model only tools the user's role may use and runs calls with
 * validated arguments. Every failure becomes an error {@link ToolResult}; results are capped in size.
 */
@Component
public class AiToolRegistry {

    static final int DEFAULT_MAX_RESULT_CHARS = 20_000;
    private static final Logger log = LoggerFactory.getLogger(AiToolRegistry.class);
    /** Stands in for arrays, objects and decimals so every schema type check rejects them. */
    private static final Object UNSUPPORTED = new Object();

    private final Map<String, AiTool> tools;
    private final ObjectMapper mapper;
    private final int maxResultChars;

    @Autowired
    AiToolRegistry(List<AiTool> tools, ObjectMapper mapper) {
        this(tools, mapper, DEFAULT_MAX_RESULT_CHARS);
    }

    AiToolRegistry(List<AiTool> tools, ObjectMapper mapper, int maxResultChars) {
        this.tools = tools.stream().sorted(Comparator.comparing(AiTool::name))
                .collect(Collectors.toMap(AiTool::name, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("Duplicate AI tool name: " + a.name());
                }, LinkedHashMap::new));
        this.mapper = mapper;
        this.maxResultChars = maxResultChars;
    }

    public List<AiTool> toolsFor(Role role) {
        return tools.values().stream().filter(t -> t.allowedFor(role)).toList();
    }

    public List<LlmToolDefinition> definitionsFor(Role role) {
        return toolsFor(role).stream().map(AiTool::definition).toList();
    }

    /** Looks up a tool the role may use; null if unknown or not allowed (e.g. to check its write flag). */
    public AiTool find(String name, Role role) {
        AiTool tool = tools.get(name);
        return tool != null && tool.allowedFor(role) ? tool : null;
    }

    public ToolResult execute(String name, String argumentsJson, ToolContext ctx) {
        AiTool tool = find(name, ctx.role());
        if (tool == null) {
            return ToolResult.error("UNKNOWN_TOOL", "No tool named '" + name + "' is available.");
        }
        Map<String, Object> args;
        try {
            args = parseArguments(argumentsJson);
        } catch (IllegalArgumentException | JacksonException e) {
            return ToolResult.error("INVALID_ARGUMENTS", "Arguments must be a JSON object.");
        }
        List<String> violations = ArgumentValidator.validate(tool.parameters(), args);
        if (!violations.isEmpty()) {
            return ToolResult.error("INVALID_ARGUMENTS", String.join("; ", violations));
        }
        try {
            return truncate(tool.execute(args, ctx));
        } catch (ToolArgumentException e) {
            return ToolResult.error("INVALID_ARGUMENTS", e.getMessage());
        } catch (HostException e) {
            return ToolResult.error(e.code(), e.getMessage());
        } catch (StackException e) {
            return ToolResult.error(e.code(), e.getMessage());
        } catch (RuntimeException e) {
            // the exception text is not forwarded (could carry internals); the type is enough for the log
            log.warn("AI tool {} failed: {}", name, e.getClass().getSimpleName());
            return ToolResult.error("TOOL_FAILED", "The tool could not be executed.");
        }
    }

    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        JsonNode node = mapper.readTree(json);
        if (node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("not an object");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        node.properties().forEach(e -> args.put(e.getKey(), toJava(e.getValue())));
        return args;
    }

    private static Object toJava(JsonNode n) {
        if (n.isNull()) {
            return null;
        }
        if (n.isString()) {
            return n.asString();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isIntegralNumber() && n.canConvertToLong()) {
            return n.asLong();
        }
        return UNSUPPORTED;
    }

    private ToolResult truncate(ToolResult result) {
        String content = result.content();
        if (content == null || content.length() <= maxResultChars) {
            return result;
        }
        int omitted = content.length() - maxResultChars;
        return new ToolResult(result.error(), content.substring(0, maxResultChars)
                + "\n...[truncated, " + omitted + " more characters omitted; narrow the request]");
    }
}
