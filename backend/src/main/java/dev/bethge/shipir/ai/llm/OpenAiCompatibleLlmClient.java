package dev.bethge.shipir.ai.llm;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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

/** {@code POST {baseUrl}/chat/completions} with {@code stream=true}, parsed from server-sent events. */
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
    private final HttpClient http;

    public OpenAiCompatibleLlmClient(LlmProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
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
                log.debug("LLM provider answered HTTP {}", status);
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

    private HttpRequest buildRequest(String baseUrl, String model, String apiKey, List<LlmMessage> messages,
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
                    "name", t.name(), "description", t.description(), "parameters", t.parameters()))).toList());
        }
        String json = mapper.writeValueAsString(body);
        return HttpRequest.newBuilder(uri)
                .timeout(properties.readTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
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
        Map<Integer, ToolCallBuilder> calls = new TreeMap<>();
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
