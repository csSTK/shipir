package dev.bethge.shipir.ai.llm;

import dev.bethge.shipir.common.net.EgressHttpClient;
import dev.bethge.shipir.common.net.EgressPolicy;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@code POST {baseUrl}/chat/completions} with {@code stream=true}, parsed from server-sent events. The base URL is
 * validated by the egress policy inside the lookup the connection uses ({@link EgressHttpClient}), so a DNS answer that
 * flips after validation cannot redirect the request, and the API key never reaches a denied host.
 */
@Component
@EnableConfigurationProperties(LlmProperties.class)
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "llm-read-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final LlmProperties properties;
    private final ObjectMapper mapper;
    private final EgressHttpClient http;

    public OpenAiCompatibleLlmClient(LlmProperties properties, ObjectMapper mapper, EgressPolicy egress) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = new EgressHttpClient(egress, properties.connectTimeout());
    }

    @PreDestroy
    void close() throws IOException {
        http.close();
    }

    @Override
    public LlmResponse chat(String baseUrl, String model, String apiKey, List<LlmMessage> messages,
                            List<LlmToolDefinition> tools, Consumer<String> onToken) {
        PreparedRequest request = buildRequest(baseUrl, model, apiKey, messages, tools);
        AtomicBoolean timedOut = new AtomicBoolean();
        try {
            // The address is validated inside the lookup the connection uses (no DNS rebinding window); a denied host
            // fails before any byte, so the API key never reaches it.
            return http.stream("POST", request.uri(), request.headers(), request.body(), properties.readTimeout(),
                    response -> handle(response, apiKey, onToken, timedOut));
        } catch (IOException e) {
            if (LlmException.isTimeout(e)) {
                throw new LlmException(LlmException.Code.TIMEOUT, "LLM provider did not answer in time");
            }
            if (timedOut.get()) {
                throw new LlmException(LlmException.Code.TIMEOUT, "LLM stream stalled");
            }
            throw new LlmException(LlmException.Code.UNREACHABLE, "LLM provider is unreachable");
        }
    }

    private LlmResponse handle(EgressHttpClient.StreamResponse response, String apiKey, Consumer<String> onToken,
                               AtomicBoolean timedOut) throws IOException {
        int status = response.status();
        if (status < 200 || status >= 300) {
            logErrorBody(status, response, apiKey);
            throw statusError(status, response.header("Retry-After"));
        }
        return readStream(response, onToken, timedOut);
    }

    private record PreparedRequest(URI uri, Map<String, String> headers, String body) {
    }

    /** Debug only: the body is read under the idle watchdog so a stalled error response cannot hang the call. */
    private void logErrorBody(int status, EgressHttpClient.StreamResponse response, String apiKey) {
        if (!log.isDebugEnabled()) {
            return;
        }
        ScheduledFuture<?> watchdog = arm(response, new AtomicBoolean());
        try {
            log.debug("LLM provider answered HTTP {}: {}", status, ProviderErrorBody.read(response.body(), apiKey));
        } finally {
            watchdog.cancel(false);
        }
    }

    private PreparedRequest buildRequest(String baseUrl, String model, String apiKey, List<LlmMessage> messages,
                                     List<LlmToolDefinition> tools) {
        URI uri;
        try {
            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            uri = URI.create(base + "/chat/completions");
            if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new LlmException(LlmException.Code.UNREACHABLE, "Invalid LLM base URL");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", true);
        body.put("messages", messages.stream().map(this::toWire).toList());
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(t -> Map.of("type", "function", "function", Map.of(
                    "name", t.name(), "description", t.description(), "parameters", sanitizeSchema(t.parameters())))).toList());
        }
        String json = mapper.writeValueAsString(body);
        return new PreparedRequest(uri, Map.of("Content-Type", "application/json",
                "Accept", "text/event-stream", "Authorization", "Bearer " + apiKey), json);
    }

    /** Providers such as Gemini reject these JSON Schema keywords; the tool definitions keep them for server-side validation. */
    private static final java.util.Set<String> UNSUPPORTED_SCHEMA_KEYWORDS = java.util.Set.of("format", "additionalProperties");

    /** Deep copy for the wire without unsupported keywords; the source maps are not modified. */
    private static Object sanitizeSchema(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (!UNSUPPORTED_SCHEMA_KEYWORDS.contains(String.valueOf(k))) {
                    copy.put(String.valueOf(k), sanitizeSchema(v));
                }
            });
            return copy;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(OpenAiCompatibleLlmClient::sanitizeSchema).toList();
        }
        return node;
    }

    private Map<String, Object> toWire(LlmMessage m) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", m.role().name().toLowerCase());
        wire.put("content", m.content());
        if (!m.toolCalls().isEmpty()) {
            wire.put("tool_calls", m.toolCalls().stream().map(c -> Map.of("id", c.id(), "type", "function",
                    "function", Map.of("name", c.name(), "arguments", c.argumentsJson()))).toList());
        }
        if (m.toolCallId() != null) {
            wire.put("tool_call_id", m.toolCallId());
        }
        return wire;
    }

    private static LlmException statusError(int status, String retryAfterHeader) {
        if (status == 401 || status == 403) {
            return new LlmException(LlmException.Code.AUTH_FAILED, "LLM provider rejected the credentials", null,
                    status);
        }
        if (status == 429) {
            return new LlmException(LlmException.Code.RATE_LIMITED, "LLM provider rate limit reached",
                    LlmException.parseRetryAfter(retryAfterHeader), status);
        }
        return new LlmException(LlmException.Code.BAD_RESPONSE, "LLM provider answered HTTP " + status, null, status);
    }

    private LlmResponse readStream(EgressHttpClient.StreamResponse response, Consumer<String> onToken, AtomicBoolean timedOut)
            throws IOException {
        StringBuilder text = new StringBuilder();
        Map<Integer, ToolCallBuilder> calls = new TreeMap<>();
        boolean sawChunk = false;
        ScheduledFuture<?>[] watchdog = {arm(response, timedOut)};
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                watchdog[0].cancel(false);
                watchdog[0] = arm(response, timedOut);
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).strip();
                if (data.equals("[DONE]")) {
                    break;
                }
                if (data.isEmpty()) {
                    continue;
                }
                sawChunk = true;
                applyChunk(parse(data), text, calls, onToken);
            }
        } finally {
            watchdog[0].cancel(false);
        }
        if (!sawChunk) {
            throw new LlmException(LlmException.Code.BAD_RESPONSE, "LLM stream contained no data");
        }
        List<LlmToolCall> toolCalls = new ArrayList<>();
        for (ToolCallBuilder b : calls.values()) {
            toolCalls.add(b.build());
        }
        return new LlmResponse(text.toString(), toolCalls);
    }

    private ScheduledFuture<?> arm(EgressHttpClient.StreamResponse response, AtomicBoolean timedOut) {
        return WATCHDOG.schedule(() -> {
            timedOut.set(true);
            response.abort(); // only unblocks the reader
        }, properties.readTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private JsonNode parse(String data) {
        try {
            return mapper.readTree(data);
        } catch (RuntimeException e) {
            throw new LlmException(LlmException.Code.BAD_RESPONSE, "LLM stream contained a malformed chunk");
        }
    }

    private static void applyChunk(JsonNode chunk, StringBuilder text, Map<Integer, ToolCallBuilder> calls,
                                   Consumer<String> onToken) {
        if (chunk.has("error")) {
            throw new LlmException(LlmException.Code.BAD_RESPONSE, "LLM provider reported an error");
        }
        JsonNode delta = chunk.path("choices").path(0).path("delta");
        JsonNode content = delta.path("content");
        if (content.isString() && !content.asString().isEmpty()) {
            text.append(content.asString());
            onToken.accept(content.asString());
        }
        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                ToolCallBuilder b = calls.computeIfAbsent(tc.path("index").asInt(calls.size()),
                        i -> new ToolCallBuilder());
                if (tc.path("id").isString()) {
                    b.id = tc.path("id").asString();
                }
                JsonNode fn = tc.path("function");
                if (fn.path("name").isString()) {
                    b.name.append(fn.path("name").asString());
                }
                if (fn.path("arguments").isString()) {
                    b.arguments.append(fn.path("arguments").asString());
                }
            }
        }
    }

    private static final class ToolCallBuilder {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        LlmToolCall build() {
            if (id == null || name.isEmpty()) {
                throw new LlmException(LlmException.Code.BAD_RESPONSE, "LLM returned an incomplete tool call");
            }
            return new LlmToolCall(id, name.toString(), arguments.isEmpty() ? "{}" : arguments.toString());
        }
    }
}
