package dev.bethge.shipir.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Native Gemini API: {@code POST {baseUrl}/models/{model}:streamGenerateContent?alt=sse}. The key travels only in the
 * {@code x-goog-api-key} header.
 */
@Component
public class GeminiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gemini-read-watchdog");
        t.setDaemon(true);
        return t;
    });
    /** JSON Schema keywords Gemini's function declarations accept (OpenAPI subset). */
    private static final Set<String> SCHEMA_KEYWORDS = Set.of("type", "description", "properties", "required",
            "items", "enum", "nullable", "minimum", "maximum", "minItems", "maxItems");

    private final LlmProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public GeminiLlmClient(LlmProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** HTTP version of the underlying client (pinned to 1.1, HTTP/2 streams hung with some providers). */
    HttpClient.Version httpVersion() {
        return http.version();
    }

    @Override
    public LlmResponse chat(String baseUrl, String model, String apiKey, List<LlmMessage> messages,
                            List<LlmToolDefinition> tools, Consumer<String> onToken) {
        HttpRequest request = buildRequest(baseUrl, model, apiKey, messages, tools);
        AtomicBoolean timedOut = new AtomicBoolean();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new LlmException(LlmException.Code.TIMEOUT, "LLM provider did not answer in time");
        } catch (IOException e) {
            throw new LlmException(LlmException.Code.UNREACHABLE, "LLM provider is unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Code.UNREACHABLE, "Request interrupted");
        }
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                logErrorBody(status, body, apiKey);
                throw statusError(status);
            }
            return readStream(body, onToken, timedOut);
        } catch (IOException e) {
            if (timedOut.get()) {
                throw new LlmException(LlmException.Code.TIMEOUT, "LLM stream stalled");
            }
            throw new LlmException(LlmException.Code.UNREACHABLE, "LLM stream was interrupted");
        }
    }

    /** Debug only: the body is read under the idle watchdog so a stalled error response cannot hang the call. */
    private void logErrorBody(int status, InputStream body, String apiKey) {
        if (!log.isDebugEnabled()) {
            return;
        }
        ScheduledFuture<?> watchdog = arm(body, new AtomicBoolean());
        try {
            log.debug("Gemini provider answered HTTP {}: {}", status, ProviderErrorBody.read(body, apiKey));
        } finally {
            watchdog.cancel(false);
        }
    }

    private HttpRequest buildRequest(String baseUrl, String model, String apiKey, List<LlmMessage> messages,
                                     List<LlmToolDefinition> tools) {
        URI uri;
        try {
            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            String name = model.startsWith("models/") ? model.substring(7) : model;
            uri = URI.create(base + "/models/" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                    + ":streamGenerateContent?alt=sse");
            if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new LlmException(LlmException.Code.UNREACHABLE, "Invalid LLM base URL");
        }
        String json = mapper.writeValueAsString(buildBody(messages, tools));
        return HttpRequest.newBuilder(uri)
                .timeout(properties.readTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
    }

    private Map<String, Object> buildBody(List<LlmMessage> messages, List<LlmToolDefinition> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<String> system = new ArrayList<>();
        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, String> toolNamesById = new LinkedHashMap<>();
        for (LlmMessage m : messages) {
            switch (m.role()) {
                case SYSTEM -> system.add(m.content());
                case USER -> contents.add(content("user", List.of(Map.of("text", m.content()))));
                case ASSISTANT -> {
                    List<Map<String, Object>> parts = new ArrayList<>();
                    if (m.content() != null && !m.content().isEmpty()) {
                        parts.add(Map.of("text", m.content()));
                    }
                    for (LlmToolCall c : m.toolCalls()) {
                        toolNamesById.put(c.id(), c.name());
                        Map<String, Object> part = new LinkedHashMap<>();
                        part.put("functionCall", Map.of("name", c.name(), "args", parseArgs(c.argumentsJson())));
                        if (c.thoughtSignature() != null && !c.thoughtSignature().isEmpty()) {
                            part.put("thoughtSignature", c.thoughtSignature());
                        }
                        parts.add(part);
                    }
                    if (!parts.isEmpty()) {
                        contents.add(content("model", parts));
                    }
                }
                case TOOL -> {
                    Map<String, Object> part = Map.of("functionResponse", Map.of(
                            "name", toolNamesById.getOrDefault(m.toolCallId(), "unknown"),
                            "response", Map.of("result", m.content() == null ? "" : m.content())));
                    Map<String, Object> last = contents.isEmpty() ? null : contents.getLast();
                    if (last != null && last.get("_tool") == Boolean.TRUE) {
                        @SuppressWarnings("unchecked")
                        List<Object> parts = (List<Object>) last.get("parts");
                        parts.add(part);
                    } else {
                        Map<String, Object> c = content("user", new ArrayList<>(List.of(part)));
                        c.put("_tool", Boolean.TRUE);
                        contents.add(c);
                    }
                }
            }
        }
        contents.forEach(c -> c.remove("_tool"));
        if (!system.isEmpty()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", String.join("\n\n", system)))));
        }
        body.put("contents", contents);
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> decls = new ArrayList<>();
            for (LlmToolDefinition t : tools) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", t.name());
                d.put("description", t.description());
                Map<String, Object> schema = sanitizeSchema(t.parameters());
                Object props = schema.get("properties");
                if (props instanceof Map<?, ?> p && !p.isEmpty()) {
                    d.put("parameters", schema);
                }
                decls.add(d);
            }
            body.put("tools", List.of(Map.of("functionDeclarations", decls)));
        }
        return body;
    }

    private static Map<String, Object> content(String role, List<?> parts) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("role", role);
        c.put("parts", parts);
        return c;
    }

    private Object parseArgs(String json) {
        try {
            JsonNode node = mapper.readTree(json == null || json.isBlank() ? "{}" : json);
            return node.isObject() ? mapper.convertValue(node, Map.class) : Map.of();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** Keeps only keywords Gemini accepts; a {@code ["x","null"]} type becomes {@code x} plus nullable. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> sanitizeSchema(Map<String, Object> schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (schema == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : schema.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!SCHEMA_KEYWORDS.contains(key)) {
                continue;
            }
            switch (key) {
                case "properties" -> {
                    if (value instanceof Map<?, ?> props) {
                        Map<String, Object> clean = new LinkedHashMap<>();
                        props.forEach((n, v) -> clean.put(String.valueOf(n),
                                v instanceof Map<?, ?> vm ? sanitizeSchema((Map<String, Object>) vm) : v));
                        out.put(key, clean);
                    }
                }
                case "items" -> {
                    if (value instanceof Map<?, ?> vm) {
                        out.put(key, sanitizeSchema((Map<String, Object>) vm));
                    }
                }
                case "type" -> {
                    if (value instanceof List<?> types) {
                        types.stream().filter(t -> !"null".equals(t)).findFirst().ifPresent(t -> out.put(key, t));
                        if (types.contains("null")) {
                            out.put("nullable", true);
                        }
                    } else {
                        out.put(key, value);
                    }
                }
                default -> out.put(key, value);
            }
        }
        return out;
    }

    private static LlmException statusError(int status) {
        if (status == 401 || status == 403) {
            return new LlmException(LlmException.Code.AUTH_FAILED, "LLM provider rejected the credentials");
        }
        if (status == 429) {
            return new LlmException(LlmException.Code.RATE_LIMITED, "LLM provider rate limit reached");
        }
        return new LlmException(LlmException.Code.BAD_RESPONSE, "LLM provider answered HTTP " + status);
    }

    private LlmResponse readStream(InputStream body, Consumer<String> onToken, AtomicBoolean timedOut)
            throws IOException {
        StringBuilder text = new StringBuilder();
        List<LlmToolCall> toolCalls = new ArrayList<>();
        boolean sawChunk = false;
        ScheduledFuture<?>[] watchdog = {arm(body, timedOut)};
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                watchdog[0].cancel(false);
                watchdog[0] = arm(body, timedOut);
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).strip();
                if (data.isEmpty()) {
                    continue;
                }
                sawChunk = true;
                applyChunk(parse(data), text, toolCalls, onToken);
            }
        } finally {
            watchdog[0].cancel(false);
        }
        if (!sawChunk) {
            throw new LlmException(LlmException.Code.BAD_RESPONSE, "LLM stream contained no data");
        }
        return new LlmResponse(text.toString(), toolCalls);
    }

    private ScheduledFuture<?> arm(InputStream body, AtomicBoolean timedOut) {
        return WATCHDOG.schedule(() -> {
            timedOut.set(true);
            try {
                body.close();
            } catch (IOException ignored) {
                // closing only unblocks the reader
            }
        }, properties.readTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private JsonNode parse(String data) {
        try {
            return mapper.readTree(data);
        } catch (RuntimeException e) {
            throw new LlmException(LlmException.Code.BAD_RESPONSE, "LLM stream contained a malformed chunk");
        }
    }

    private void applyChunk(JsonNode chunk, StringBuilder text, List<LlmToolCall> toolCalls,
                            Consumer<String> onToken) {
        if (chunk.has("error")) {
            throw new LlmException(LlmException.Code.BAD_RESPONSE, "LLM provider reported an error");
        }
        JsonNode parts = chunk.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray()) {
            return;
        }
        for (JsonNode part : parts) {
            JsonNode t = part.path("text");
            if (t.isString() && !t.asString().isEmpty() && !part.path("thought").asBoolean(false)) {
                text.append(t.asString());
                onToken.accept(t.asString());
            }
            JsonNode fc = part.path("functionCall");
            if (fc.isObject()) {
                String name = fc.path("name").asString("");
                if (name.isEmpty()) {
                    throw new LlmException(LlmException.Code.BAD_RESPONSE, "LLM returned an incomplete tool call");
                }
                JsonNode args = fc.path("args");
                String argsJson = args.isObject() ? mapper.writeValueAsString(args) : "{}";
                toolCalls.add(new LlmToolCall("gemini-" + UUID.randomUUID(), name, argsJson,
                        part.path("thoughtSignature").asString(null)));
            }
        }
    }
}
