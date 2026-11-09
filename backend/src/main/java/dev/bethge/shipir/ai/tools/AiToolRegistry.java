package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.ai.llm.LlmToolDefinition;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.common.exception.CodedException;
import dev.bethge.shipir.common.security.AiInvocationContext;
import dev.bethge.shipir.compose.DeploymentException;
import dev.bethge.shipir.compose.EnvFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Entry point for the chat feature: offers the model only tools the user's role may use and runs calls with
 * validated arguments. Every failure becomes an error {@link ToolResult}; results are capped in size ({@link AiToolProperties#maxResultChars()}).
 *
 * <p>Confirmation gate: {@link #execute} refuses write tools. A write call goes through {@link #propose}, which
 * validates it and stores it under an unguessable single-use token bound to the user and chat session; only
 * {@link #executeConfirmed} (called after the user said yes) runs it, as that user with the AI marker set so the
 * audit entries say {@code via: AI}. The gate lives here, so the model cannot talk its way around it. Pending
 * actions are in memory (lost on restart, the user simply asks again) and expire after {@link #PENDING_TTL}.
 */
@Component
@EnableConfigurationProperties(AiToolProperties.class)
public class AiToolRegistry {

    static final int DEFAULT_MAX_RESULT_CHARS = AiToolProperties.DEFAULT_MAX_RESULT_CHARS;
    static final Duration PENDING_TTL = Duration.ofMinutes(10);
    static final int MAX_PENDING = 1000;
    private static final Logger log = LoggerFactory.getLogger(AiToolRegistry.class);
    /** Stands in for arrays, objects and decimals so every schema type check rejects them. */
    private static final Object UNSUPPORTED = new Object();

    private final Map<String, AiTool> tools;
    private final ObjectMapper mapper;
    private final int maxResultChars;
    private final Clock clock;
    private final DeploymentEnvValues envValues;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(PendingAction view, AiTool tool, Map<String, Object> args, UUID userId,
                           UUID chatSessionId) {
    }

    private record Prepared(AiTool tool, Map<String, Object> args, ToolResult error) {
    }

    @Autowired
    AiToolRegistry(List<AiTool> tools, ObjectMapper mapper, AiToolProperties properties, Clock clock,
                   DeploymentEnvValues envValues) {
        this(tools, mapper, properties.maxResultChars(), clock, envValues);
    }

    AiToolRegistry(List<AiTool> tools, ObjectMapper mapper, AiToolProperties properties, Clock clock) {
        this(tools, mapper, properties.maxResultChars(), clock);
    }

    AiToolRegistry(List<AiTool> tools, ObjectMapper mapper) {
        this(tools, mapper, DEFAULT_MAX_RESULT_CHARS, Clock.systemUTC());
    }

    AiToolRegistry(List<AiTool> tools, ObjectMapper mapper, int maxResultChars) {
        this(tools, mapper, maxResultChars, Clock.systemUTC());
    }

    AiToolRegistry(List<AiTool> tools, ObjectMapper mapper, int maxResultChars, Clock clock) {
        this(tools, mapper, maxResultChars, clock, args -> List.of());
    }

    AiToolRegistry(List<AiTool> tools, ObjectMapper mapper, int maxResultChars, Clock clock,
                   DeploymentEnvValues envValues) {
        this.tools = tools.stream().sorted(Comparator.comparing(AiTool::name))
                .collect(Collectors.toMap(AiTool::name, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("Duplicate AI tool name: " + a.name());
                }, LinkedHashMap::new));
        this.mapper = mapper;
        this.maxResultChars = maxResultChars;
        this.clock = clock;
        this.envValues = envValues;
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

    /** Runs a read tool. Write tools are refused here: they need {@link #propose} and {@link #executeConfirmed}. */
    public ToolResult execute(String name, String argumentsJson, ToolContext ctx) {
        Prepared p = prepare(name, argumentsJson, ctx);
        if (p.error != null) {
            return p.error;
        }
        if (p.tool.write()) {
            return ToolResult.error("CONFIRMATION_REQUIRED",
                    "'" + name + "' changes state and needs the user's confirmation before it runs.");
        }
        return runAs(ctx, () -> run(p.tool, p.args, ctx));
    }

    /**
     * Validates a write tool call and parks it until the user confirms. Read tools and calls that would fail anyway
     * (unknown tool, role, bad arguments) are rejected right away with an error for the model.
     *
     * @param chatSessionId recorded as {@code chatSessionId} in the audit entries of the confirmed call
     */
    public ProposalResult propose(String name, String argumentsJson, ToolContext ctx, UUID chatSessionId) {
        Prepared p = prepare(name, argumentsJson, ctx);
        if (p.error != null) {
            return ProposalResult.rejected(p.error);
        }
        if (!p.tool.write()) {
            return ProposalResult.rejected(ToolResult.error("NOT_A_WRITE_TOOL",
                    "'" + name + "' does not need confirmation; call it directly."));
        }
        Instant now = clock.instant();
        pending.values().removeIf(x -> !x.view.expiresAt().isAfter(now));
        if (pending.size() >= MAX_PENDING) {
            return ProposalResult.rejected(ToolResult.error("TOO_MANY_PENDING", "Too many actions await confirmation."));
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        PendingAction view = new PendingAction(token, name, headline(p.tool, display(p.args)), display(p.args), now.plus(PENDING_TTL));
        pending.put(token, new Pending(view, p.tool, p.args, ctx.userId(), chatSessionId));
        return ProposalResult.accepted(view);
    }

    /**
     * Runs a confirmed write call, once. Fails (without consuming the token) when the token belongs to another user;
     * unknown, used and expired tokens are indistinguishable to the caller. The role is checked again, and the call
     * runs with a security context for the user and the AI marker bound (see {@link AiInvocationContext}).
     */
    public ToolResult executeConfirmed(String token, ToolContext ctx) {
        Pending p = token == null ? null : pending.get(token);
        if (p == null || !p.userId.equals(ctx.userId())) {
            return unknownConfirmation();
        }
        if (!pending.remove(token, p) || !p.view.expiresAt().isAfter(clock.instant())) {
            return unknownConfirmation();
        }
        if (!p.tool.allowedFor(ctx.role())) {
            return ToolResult.error("UNKNOWN_TOOL", "No tool named '" + p.tool.name() + "' is available.");
        }
        return AiInvocationContext.callAs(p.chatSessionId, () -> runAs(ctx, () -> run(p.tool, p.args, ctx)));
    }

    /** Discards a pending action (user said no). @return false if there was none for this user */
    public boolean cancel(String token, ToolContext ctx) {
        Pending p = token == null ? null : pending.get(token);
        return p != null && p.userId.equals(ctx.userId()) && pending.remove(token, p);
    }

    private static ToolResult unknownConfirmation() {
        return ToolResult.error("UNKNOWN_CONFIRMATION", "This confirmation is unknown, already used or expired.");
    }

    private Prepared prepare(String name, String argumentsJson, ToolContext ctx) {
        AiTool tool = find(name, ctx.role());
        if (tool == null) {
            return new Prepared(null, null, ToolResult.error("UNKNOWN_TOOL", "No tool named '" + name + "' is available."));
        }
        Map<String, Object> args;
        try {
            args = parseArguments(argumentsJson);
        } catch (IllegalArgumentException | JacksonException e) {
            return new Prepared(null, null, ToolResult.error("INVALID_ARGUMENTS", "Arguments must be a JSON object."));
        }
        List<String> violations = ArgumentValidator.validate(tool.parameters(), args);
        if (!violations.isEmpty()) {
            return new Prepared(null, null, ToolResult.error("INVALID_ARGUMENTS", String.join("; ", violations)));
        }
        return new Prepared(tool, args, null);
    }

    /** The tool's description plus the effective arguments, so the Confirm card says what this very call will do. */
    private static String headline(AiTool tool, Map<String, Object> shown) {
        if (shown.isEmpty()) {
            return tool.description();
        }
        StringBuilder sb = new StringBuilder(tool.description()).append(" (");
        shown.forEach((k, v) -> {
            String value = String.valueOf(v);
            sb.append(k).append('=').append(value.length() > 80 ? value.substring(0, 80) + "..." : value).append(", ");
        });
        sb.setLength(sb.length() - 2);
        return sb.append(')').toString();
    }

    private static Map<String, Object> display(Map<String, Object> args) {
        Map<String, Object> shown = new LinkedHashMap<>(args);
        shown.computeIfPresent("env", (k, v) -> "<hidden>");
        return shown;
    }

    /** Runs with a security context for the user so services and {@code AuditAspect} see them; restores the old one. */
    private static <T> T runAs(ToolContext ctx, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                ctx.username(), null, List.of(new SimpleGrantedAuthority("ROLE_" + ctx.role().name())))));
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private ToolResult run(AiTool tool, Map<String, Object> args, ToolContext ctx) {
        try {
            return truncate(tool.execute(args, ctx));
        } catch (ToolArgumentException e) {
            return ToolResult.error("INVALID_ARGUMENTS", e.getMessage());
        } catch (DeploymentException e) {
            // details name the offending fields (e.g. "ref: must not be blank") so the model can correct the call
            return ToolResult.error(e.code(), deploymentErrorMessage(e, args));
        } catch (RuntimeException e) {
            if (e instanceof CodedException coded) {
                // every feature exception with a stable code (host, config file, permission, webhook, git)
                return ToolResult.error(coded.code(), e.getMessage());
            }
            // the exception text is not forwarded (could carry internals); the type is enough for the log
            log.warn("AI tool {} failed: {}", tool.name(), e.getClass().getSimpleName());
            // the message can name paths or values, so it stays at DEBUG; arguments and stack traces are never logged
            log.debug("AI tool {} failed: {}: {}", tool.name(), e.getClass().getSimpleName(), e.getMessage());
            return ToolResult.error("TOOL_FAILED", "The tool could not be executed.");
        }
    }

    /** Values shorter than this are not redacted: they would blank out ordinary words ("1", "on", "db"). */
    static final int MIN_REDACTED_VALUE_LENGTH = 4;

    /**
     * {@code docker compose config} echoes interpolated values in its errors (e.g. {@code parsing "hunter2": invalid
     * syntax} for {@code mem_limit: ${SECRET}}). The details stay so the model can fix the file, but every env value of
     * the deployment, from the stored env and from an {@code env} argument of the same call, is replaced with
     * {@code ***}. Only the AI path redacts; the REST API still shows the user their own error.
     */
    private String deploymentErrorMessage(DeploymentException e, Map<String, Object> args) {
        if (e.details().isEmpty()) {
            return e.getMessage();
        }
        try {
            return redactEnvValues(e.getMessage() + ": " + String.join("; ", e.details()), args);
        } catch (RuntimeException failure) {
            // fail closed: if the env values cannot be determined, the details (which may echo them) are dropped
            log.warn("AI tool error details dropped, env redaction failed: {}", failure.getClass().getSimpleName());
            return e.getMessage();
        }
    }

    private String redactEnvValues(String message, Map<String, Object> args) {
        Set<String> values = new HashSet<>();
        for (String value : envValues.valuesFor(args)) {
            values.add(value == null ? null : value.replace("\r", ""));
        }
        if (args.get("env") instanceof String text) {
            EnvFile.parse(text).forEach(entry -> values.add(entry.value() == null ? null : entry.value().replace("\r", "")));
        }
        String redacted = message;
        // longest first, so a value that contains another one is replaced whole
        for (String value : values.stream().filter(v -> v != null && v.length() >= MIN_REDACTED_VALUE_LENGTH)
                .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            redacted = redacted.replace(value, "***");
        }
        return redacted;
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
        if (n.isArray() && java.util.stream.StreamSupport.stream(n.spliterator(), false).allMatch(JsonNode::isString)) {
            // only arrays of strings (e.g. profile names); anything else stays unsupported and fails validation
            return java.util.stream.StreamSupport.stream(n.spliterator(), false).map(JsonNode::asString).toList();
        }
        return UNSUPPORTED;
    }

    private ToolResult truncate(ToolResult result) {
        String content = result.content();
        if (content == null || content.length() <= maxResultChars) {
            return result;
        }
        ToolResult shortened = dropTrailingItems(result, content);
        if (shortened != null) {
            return shortened;
        }
        int cut = maxResultChars;
        if (cut > 0 && Character.isHighSurrogate(content.charAt(cut - 1))) {
            cut--; // never split a surrogate pair
        }
        int omitted = content.length() - cut;
        return new ToolResult(result.error(), content.substring(0, cut)
                + "\n...[RESULT TRUNCATED: the result was cut (truncated), " + omitted + " more characters omitted. "
                + "Do not assume the list is complete; narrow the request (filters, smaller limit, a single id)]");
    }

    /**
     * Keeps a cut list result valid JSON: drops whole trailing items of the top-level array (wrapped as {@code items})
     * or of the array field with the largest serialized size of a top-level object, keeping the largest prefix that
     * fits (binary search, so only O(log n) serializations), and adds an omitted-items count and a note.
     * The keys are {@code omittedItems} and {@code note}; if the result already has a field of that name it is never
     * overwritten, the keys {@code truncatedOmittedItems} / {@code truncationNote} (numbered further if needed) are
     * used instead. Returns null when the content has no such array or not even one item fits (caller falls back to
     * a char cut).
     */
    private ToolResult dropTrailingItems(ToolResult result, String content) {
        try {
            JsonNode root = mapper.readTree(content);
            ObjectNode holder;
            String field;
            if (root.isArray()) {
                holder = mapper.createObjectNode();
                holder.set("items", root);
                field = "items";
            } else if (root.isObject()) {
                holder = (ObjectNode) root;
                field = null;
                int largest = -1;
                for (Map.Entry<String, JsonNode> e : holder.properties()) {
                    if (e.getValue().isArray() && e.getValue().size() > 0) {
                        int size = mapper.writeValueAsString(e.getValue()).length();
                        if (size > largest) {
                            largest = size;
                            field = e.getKey();
                        }
                    }
                }
                if (field == null) {
                    return null;
                }
            } else {
                return null;
            }
            ArrayNode all = (ArrayNode) holder.get(field);
            int total = all.size();
            String countKey = freeKey(holder, "omittedItems", "truncatedOmittedItems");
            String noteKey = freeKey(holder, "note", "truncationNote");
            holder.put(countKey, 0);
            holder.put(noteKey, "RESULT TRUNCATED: the result was cut (truncated), the last items of '" + field
                    + "' were omitted. Do not assume the list is complete; narrow the request (filters, smaller limit, a single id)");
            int lo = 0; // largest kept count known to fit (0 = none yet)
            String best = null;
            int hi = total - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                String out = serializeWithPrefix(holder, field, all, mid, countKey, total);
                if (out.length() <= maxResultChars) {
                    lo = mid;
                    best = out;
                } else {
                    hi = mid - 1;
                }
            }
            return best == null ? null : new ToolResult(result.error(), best);
        } catch (JacksonException e) {
            return null;
        }
    }

    private String serializeWithPrefix(ObjectNode holder, String field, ArrayNode all, int keep, String countKey,
            int total) {
        ArrayNode prefix = mapper.createArrayNode();
        for (int i = 0; i < keep; i++) {
            prefix.add(all.get(i));
        }
        holder.set(field, prefix);
        holder.put(countKey, total - keep);
        return mapper.writeValueAsString(holder);
    }

    private static String freeKey(ObjectNode holder, String preferred, String fallback) {
        if (!holder.has(preferred)) {
            return preferred;
        }
        String key = fallback;
        for (int n = 2; holder.has(key); n++) {
            key = fallback + n;
        }
        return key;
    }
}
