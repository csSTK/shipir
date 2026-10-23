package dev.bethge.shipir.ai.chat;

import dev.bethge.shipir.ai.llm.LlmClient;
import dev.bethge.shipir.ai.llm.LlmException;
import dev.bethge.shipir.ai.llm.LlmMessage;
import dev.bethge.shipir.ai.llm.LlmResponse;
import dev.bethge.shipir.ai.llm.LlmToolCall;
import dev.bethge.shipir.ai.settings.AiConfig;
import dev.bethge.shipir.ai.settings.AiSettingsService;
import dev.bethge.shipir.ai.tools.AiTool;
import dev.bethge.shipir.ai.tools.AiToolRegistry;
import dev.bethge.shipir.ai.tools.ProposalResult;
import dev.bethge.shipir.ai.tools.ToolContext;
import dev.bethge.shipir.ai.tools.ToolResult;
import dev.bethge.shipir.common.security.SecretCipher;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The agent loop of the chat assistant. A run (virtual thread) feeds the stored history to the model with the
 * role-filtered tools: read tools are executed right away, a write tool call is stored as a PENDING message and
 * the run pauses. Nothing changes until the user confirms ({@link #confirm}), which executes the tool as that
 * user inside the request and resumes the loop; {@link #reject} tells the model the user said no.
 *
 * <p>One run or confirmation per session at a time ({@code CHAT_RUN_IN_PROGRESS}). The registry keeps pending
 * actions only in memory, so the PENDING message stores tool name, raw arguments and token; if the token is gone
 * (restart, expiry) the call is proposed again and executed right away, since the user is confirming now and the
 * role is checked again. Every message is persisted before its stream event is sent.
 */
@Service
@EnableConfigurationProperties(ChatProperties.class)
public class ChatOrchestrator {

    static final String SYSTEM_PROMPT = """
            You are the Shipir assistant, built into a web platform for managing Docker environments, containers \
            and Compose deployments. You act on behalf of the signed-in user and have exactly their permissions; \
            tools you do not see are not available to them.
            Use the read tools to look things up instead of guessing. Tools that change something never run \
            immediately: the user is shown the action and must confirm it first, so propose the change and wait \
            for the outcome. If the user rejects an action, accept it and do not retry it unprompted.
            Never ask for, repeat or store secrets such as passwords, tokens, API keys or private keys, and do not \
            put secrets into tool arguments. If something needs a secret, tell the user to enter it in the \
            Shipir settings themselves.
            Be concise and say plainly when a tool call failed.""";

    static final String ENV_CIPHERTEXT = "envCiphertext";
    static final String REJECTED_OUTCOME = "The user rejected this action. It was not executed.";
    private static final String MAX_STEPS_TEXT =
            "I stopped after %d steps without reaching an answer. Ask me to continue if you want me to go on.";
    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private final ChatSessionService sessions;
    private final AiSettingsService settings;
    private final AiToolRegistry tools;
    private final LlmClient llm;
    private final ChatEventPublisher events;
    private final ObjectMapper mapper;
    private final ChatProperties properties;
    private final SecretCipher cipher;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    ChatOrchestrator(ChatSessionService sessions, AiSettingsService settings, AiToolRegistry tools, LlmClient llm,
                     ChatEventPublisher events, ObjectMapper mapper, ChatProperties properties,
                     SecretCipher cipher) {
        this.sessions = sessions;
        this.settings = settings;
        this.tools = tools;
        this.llm = llm;
        this.events = events;
        this.mapper = mapper;
        this.properties = properties;
        this.cipher = cipher;
    }

    /** Outcome of confirming or rejecting an action; {@code runId} is set when the loop resumed. */
    public record ActionOutcome(UUID messageId, ChatMessageStatus status, boolean error, String result, UUID runId) {
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Stores the user message and starts a run. @return the run id */
    public UUID send(ToolContext ctx, UUID sessionId, String content) {
        sessions.findOwned(ctx.userId(), sessionId);
        requireConfigured();
        acquire(sessionId);
        try {
            if (hasPending(sessionId)) {
                throw new ChatSessionException(HttpStatus.CONFLICT, "ACTION_PENDING",
                        "Confirm or reject the pending action first");
            }
            sessions.appendMessage(sessionId, ChatRole.USER, content, null, null, null);
            sessions.titleFromFirstMessage(sessionId, content);
            return startRun(ctx, sessionId);
        } catch (RuntimeException e) {
            running.remove(sessionId);
            throw e;
        }
    }

    public ActionOutcome confirm(ToolContext ctx, UUID sessionId, UUID messageId) {
        sessions.findOwned(ctx.userId(), sessionId);
        acquire(sessionId);
        boolean handedOver = false;
        try {
            ChatMessage message = requirePending(sessionId, messageId);
            JsonNode call = storedCall(message);
            String name = call.path("name").asString("");
            String arguments = restoreEnv(call);
            ToolResult result = tools.executeConfirmed(call.path("token").asString(""), ctx);
            if (result.error() && result.content().contains("\"UNKNOWN_CONFIRMATION\"")) {
                ProposalResult again = tools.propose(name, arguments, ctx, sessionId);
                result = again.accepted() ? tools.executeConfirmed(again.action().token(), ctx) : again.error();
            }
            sessions.resolvePending(messageId, ChatMessageStatus.CONFIRMED, result.content());
            events.publish(sessionId, ChatStreamEvent.toolResult(UUID.randomUUID(), name, result.error()));
            UUID runId = null;
            if (!hasPending(sessionId)) {
                runId = startRun(ctx, sessionId);
                handedOver = true;
            }
            return new ActionOutcome(messageId, ChatMessageStatus.CONFIRMED, result.error(), result.content(), runId);
        } finally {
            if (!handedOver) {
                running.remove(sessionId);
            }
        }
    }

    public ActionOutcome reject(ToolContext ctx, UUID sessionId, UUID messageId) {
        sessions.findOwned(ctx.userId(), sessionId);
        acquire(sessionId);
        boolean handedOver = false;
        try {
            ChatMessage message = requirePending(sessionId, messageId);
            tools.cancel(storedCall(message).path("token").asString(""), ctx);
            sessions.resolvePending(messageId, ChatMessageStatus.REJECTED, REJECTED_OUTCOME);
            UUID runId = null;
            if (!hasPending(sessionId)) {
                runId = startRun(ctx, sessionId);
                handedOver = true;
            }
            return new ActionOutcome(messageId, ChatMessageStatus.REJECTED, false, REJECTED_OUTCOME, runId);
        } finally {
            if (!handedOver) {
                running.remove(sessionId);
            }
        }
    }

    boolean isRunning(UUID sessionId) {
        return running.contains(sessionId);
    }

    private void requireConfigured() {
        if (settings.activeConfig().isEmpty()) {
            throw new ChatSessionException(HttpStatus.CONFLICT, "AI_NOT_CONFIGURED",
                    "The AI assistant is not configured");
        }
    }

    private void acquire(UUID sessionId) {
        if (!running.add(sessionId)) {
            throw new ChatSessionException(HttpStatus.CONFLICT, "CHAT_RUN_IN_PROGRESS",
                    "The assistant is still working on this chat");
        }
    }

    private ChatMessage requirePending(UUID sessionId, UUID messageId) {
        ChatMessage message = sessions.findMessage(sessionId, messageId);
        if (message.getStatus() != ChatMessageStatus.PENDING) {
            throw new ChatSessionException(HttpStatus.CONFLICT, "ACTION_NOT_PENDING",
                    "This action was already confirmed or rejected");
        }
        return message;
    }

    private boolean hasPending(UUID sessionId) {
        return sessions.history(sessionId).stream().anyMatch(m -> m.getStatus() == ChatMessageStatus.PENDING);
    }

    private JsonNode storedCall(ChatMessage message) {
        JsonNode calls = mapper.readTree(message.getToolCalls());
        return calls.isArray() && !calls.isEmpty() ? calls.get(0) : mapper.createObjectNode();
    }

    /** Starts the loop on a virtual thread; the session lock is held by the caller and released when the run ends. */
    private UUID startRun(ToolContext ctx, UUID sessionId) {
        UUID runId = UUID.randomUUID();
        try {
            executor.execute(() -> runSafely(ctx, sessionId, runId));
        } catch (RuntimeException e) {
            running.remove(sessionId);
            throw e;
        }
        return runId;
    }

    private void runSafely(ToolContext ctx, UUID sessionId, UUID runId) {
        String outcome = "COMPLETE";
        String errorCode = null;
        try {
            errorCode = loop(ctx, sessionId, runId);
            if (errorCode != null) {
                outcome = "ERROR";
            } else if (hasPending(sessionId)) {
                outcome = "PAUSED";
            }
        } catch (RuntimeException e) {
            log.warn("Chat run failed for session {}: {}", sessionId, e.getClass().getSimpleName());
            outcome = "ERROR";
            errorCode = "INTERNAL_ERROR";
            recordError(sessionId, errorCode, "The assistant ran into an internal error.");
        } finally {
            running.remove(sessionId);
        }
        events.publish(sessionId, ChatStreamEvent.done(runId, outcome, errorCode));
    }

    /** @return null on a clean end, else the error code that was stored as an error message */
    private String loop(ToolContext ctx, UUID sessionId, UUID runId) {
        for (int step = 0; step < properties.maxIterations(); step++) {
            AiConfig config = settings.activeConfig().orElse(null);
            if (config == null) {
                recordError(sessionId, "AI_NOT_CONFIGURED", "The AI assistant is not configured.");
                return "AI_NOT_CONFIGURED";
            }
            LlmResponse response;
            try {
                response = llm.chat(config.baseUrl(), config.model(), config.apiKey(), llmHistory(sessionId),
                        tools.definitionsFor(ctx.role()), token -> events.publish(sessionId,
                                ChatStreamEvent.token(runId, token)));
            } catch (LlmException e) {
                recordError(sessionId, e.code().name(), "The language model could not be reached or refused the request.");
                return e.code().name();
            }
            String text = response.content() == null ? "" : response.content();
            if (response.toolCalls().isEmpty()) {
                sessions.appendMessage(sessionId, ChatRole.ASSISTANT,
                        text.isBlank() ? "The model returned no answer." : text, null, null, null);
                return null;
            }
            sessions.appendMessage(sessionId, ChatRole.ASSISTANT, text, callsJson(response.toolCalls()), null, null);
            if (handleToolCalls(ctx, sessionId, runId, response.toolCalls())) {
                return null;
            }
        }
        sessions.appendMessage(sessionId, ChatRole.ASSISTANT,
                MAX_STEPS_TEXT.formatted(properties.maxIterations()), null, null, null);
        return null;
    }

    /** @return true if at least one write call now waits for the user, so the run must pause */
    private boolean handleToolCalls(ToolContext ctx, UUID sessionId, UUID runId, List<LlmToolCall> calls) {
        boolean pending = false;
        for (LlmToolCall call : calls) {
            events.publish(sessionId, ChatStreamEvent.toolCall(runId, call.name()));
            AiTool tool = tools.find(call.name(), ctx.role());
            if (tool != null && tool.write()) {
                if (hasEnv(call.argumentsJson()) && !cipher.isConfigured()) {
                    storeToolResult(sessionId, runId, call, ToolResult.error("ENCRYPTION_KEY_MISSING",
                            "Variables cannot be handled through the chat because no encryption key is configured."));
                    continue;
                }
                ProposalResult proposal = tools.propose(call.name(), call.argumentsJson(), ctx, sessionId);
                if (proposal.accepted()) {
                    var action = proposal.action();
                    ChatMessage stored = sessions.appendMessage(sessionId, ChatRole.TOOL, action.description(),
                            pendingJson(call, action.token(), action.description()), call.id(),
                            ChatMessageStatus.PENDING);
                    events.publish(sessionId, ChatStreamEvent.pending(runId, call.name(), action.description(),
                            stored.getId()));
                    pending = true;
                    continue;
                }
                storeToolResult(sessionId, runId, call, proposal.error());
                continue;
            }
            storeToolResult(sessionId, runId, call, tools.execute(call.name(), call.argumentsJson(), ctx));
        }
        return pending;
    }

    private void storeToolResult(UUID sessionId, UUID runId, LlmToolCall call, ToolResult result) {
        sessions.appendMessage(sessionId, ChatRole.TOOL, result.content(), null, call.id(), null);
        events.publish(sessionId, ChatStreamEvent.toolResult(runId, call.name(), result.error()));
    }

    private void recordError(UUID sessionId, String code, String message) {
        try {
            sessions.appendMessage(sessionId, ChatRole.ASSISTANT, "Error (" + code + "): " + message, null, null, null);
        } catch (RuntimeException e) {
            log.warn("Could not store chat error for session {}", sessionId);
        }
    }

    private List<LlmMessage> llmHistory(UUID sessionId) {
        List<LlmMessage> out = new ArrayList<>();
        out.add(LlmMessage.system(SYSTEM_PROMPT));
        for (ChatMessage m : sessions.history(sessionId)) {
            switch (m.getRole()) {
                case USER -> out.add(LlmMessage.user(m.getContent()));
                case ASSISTANT -> out.add(LlmMessage.assistant(m.getContent(), parseCalls(m.getToolCalls())));
                case TOOL -> out.add(LlmMessage.tool(m.getToolCallId(), m.getContent()));
            }
        }
        return out;
    }

    private List<LlmToolCall> parseCalls(String json) {
        List<LlmToolCall> calls = new ArrayList<>();
        if (json != null) {
            for (JsonNode n : mapper.readTree(json)) {
                calls.add(new LlmToolCall(n.path("id").asString(), n.path("name").asString(),
                        n.path("arguments").asString("{}")));
            }
        }
        return calls;
    }

    private ObjectNode argumentsOf(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            return n instanceof ObjectNode o ? o : mapper.createObjectNode();
        } catch (RuntimeException e) {
            return mapper.createObjectNode();
        }
    }

    private boolean hasEnv(String argumentsJson) {
        JsonNode env = argumentsOf(argumentsJson).get("env");
        return env != null && !env.isNull();
    }

    /** Arguments as stored: the {@code env} value (secrets) is never written to the chat tables. */
    private String maskEnv(String argumentsJson) {
        ObjectNode args = argumentsOf(argumentsJson);
        if (!hasEnv(argumentsJson)) {
            return argumentsJson;
        }
        args.put("env", ChatSessionService.HIDDEN);
        return args.toString();
    }

    /** The real arguments of a stored pending call: masked arguments plus the decrypted {@code env}. */
    private String restoreEnv(JsonNode call) {
        String stored = call.path("arguments").asString("{}");
        String ciphertext = call.path(ENV_CIPHERTEXT).asString(null);
        if (ciphertext == null) {
            return stored;
        }
        ObjectNode args = argumentsOf(stored);
        args.set("env", mapper.readTree(cipher.decrypt(Base64.getDecoder().decode(ciphertext))));
        return args.toString();
    }

    private String callsJson(List<LlmToolCall> calls) {
        ArrayNode array = mapper.createArrayNode();
        for (LlmToolCall c : calls) {
            array.add(mapper.createObjectNode().put("id", c.id()).put("name", c.name())
                    .put("arguments", maskEnv(c.argumentsJson())));
        }
        return array.toString();
    }

    private String pendingJson(LlmToolCall call, String token, String description) {
        ObjectNode node = mapper.createObjectNode().put("id", call.id()).put("name", call.name())
                .put("arguments", maskEnv(call.argumentsJson())).put("token", token)
                .put("description", description);
        JsonNode env = argumentsOf(call.argumentsJson()).get("env");
        if (env != null && !env.isNull()) {
            node.put(ENV_CIPHERTEXT, Base64.getEncoder().encodeToString(cipher.encrypt(env.toString())));
        }
        return mapper.createArrayNode().add(node).toString();
    }
}
