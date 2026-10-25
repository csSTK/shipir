package dev.bethge.shipir.ai.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiCompatibleLlmClientTest {

    private static final String KEY = "sk-secret-key-123456";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private HttpServer server;
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile HttpHandler handler;
    private volatile String retryAfterHeader;
    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> handler.handle(ex));
        server.start();
        client = new OpenAiCompatibleLlmClient(new LlmProperties(Duration.ofSeconds(2), Duration.ofMillis(500)), MAPPER, policy(true));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private void respond(int status, String contentType, String body) {
        try {
        } catch (IllegalArgumentException ignored) {
            // first registration
        }
        handler = ex -> {
            authHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", contentType);
            if (retryAfterHeader != null) {
                ex.getResponseHeaders().add("Retry-After", retryAfterHeader);
            }
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        };
    }

    private void sse(String... dataLines) {
        StringBuilder sb = new StringBuilder();
        for (String d : dataLines) {
            sb.append("data: ").append(d).append("\n\n");
        }
        respond(200, "text/event-stream", sb.toString());
    }

    private LlmResponse call(List<String> tokens) {
        return client.chat(baseUrl(), "m", KEY, List.of(LlmMessage.user("hi")), List.of(), tokens::add);
    }

    private static String delta(String json) {
        return "{\"choices\":[{\"index\":0,\"delta\":" + json + "}]}";
    }

    @Test
    void usesHttp11Only() {
        assertThat(client.httpVersion()).isEqualTo(java.net.http.HttpClient.Version.HTTP_1_1);
    }

    @Test
    void streamsTextTokensAndSendsAuthAndBody() {
        sse(delta("{\"role\":\"assistant\",\"content\":\"Hel\"}"), delta("{\"content\":\"lo\"}"),
                delta("{}"), "[DONE]");
        List<String> tokens = new ArrayList<>();

        LlmResponse r = client.chat(baseUrl() + "/", "gpt-x", KEY,
                List.of(LlmMessage.system("s"), LlmMessage.user("hi")),
                List.of(new LlmToolDefinition("list", "Lists", Map.of("type", "object"))), tokens::add);

        assertThat(tokens).containsExactly("Hel", "lo");
        assertThat(r.content()).isEqualTo("Hello");
        assertThat(r.toolCalls()).isEmpty();
        assertThat(authHeader.get()).isEqualTo("Bearer " + KEY);
        JsonNode body = MAPPER.readTree(requestBody.get());
        assertThat(body.path("model").asString()).isEqualTo("gpt-x");
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("messages").get(0).path("role").asString()).isEqualTo("system");
        assertThat(body.path("tools").get(0).path("function").path("name").asString()).isEqualTo("list");
    }

    @Test
    void stripsFormatAndAdditionalPropertiesFromToolSchemasAtEveryDepth() {
        sse(delta("{\"content\":\"ok\"}"), "[DONE]");
        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("id"),
                "properties", Map.of(
                        "id", Map.of("type", "string", "format", "uuid", "description", "the id"),
                        "n", Map.of("type", "integer", "minimum", 1, "maximum", 5),
                        "mode", Map.of("type", "string", "enum", List.of("a", "b")),
                        "items", Map.of("type", "array", "items", Map.of(
                                "type", "object", "additionalProperties", false,
                                "properties", Map.of("x", Map.of("type", "string", "format", "date-time"))))));

        client.chat(baseUrl(), "m", KEY, List.of(LlmMessage.user("q")),
                List.of(new LlmToolDefinition("t", "d", schema)), t -> { });

        JsonNode params = MAPPER.readTree(requestBody.get()).path("tools").get(0).path("function").path("parameters");
        assertThat(params.toString()).doesNotContain("\"format\"").doesNotContain("additionalProperties");
        assertThat(params.path("type").asString()).isEqualTo("object");
        assertThat(params.path("required").get(0).asString()).isEqualTo("id");
        assertThat(params.path("properties").path("id").path("description").asString()).isEqualTo("the id");
        assertThat(params.path("properties").path("n").path("minimum").asInt()).isEqualTo(1);
        assertThat(params.path("properties").path("n").path("maximum").asInt()).isEqualTo(5);
        assertThat(params.path("properties").path("mode").path("enum").size()).isEqualTo(2);
        assertThat(params.path("properties").path("items").path("items").path("properties").path("x")
                .path("type").asString()).isEqualTo("string");
        // source definition is untouched
        assertThat(schema).containsKey("additionalProperties");
    }

    @Test
    void assemblesToolCallFromSplitArgumentChunks() {
        sse(delta("{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"list_containers\",\"arguments\":\"\"}}]}"),
                delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"host\"}}]}"),
                delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"Id\\\":\\\"a\\\"}\"}}]}"),
                "[DONE]");

        LlmResponse r = call(new ArrayList<>());

        assertThat(r.toolCalls()).containsExactly(new LlmToolCall("call_1", "list_containers", "{\"hostId\":\"a\"}"));
    }

    @Test
    void assemblesMultipleInterleavedToolCalls() {
        sse(delta("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"one\",\"arguments\":\"{\\\"x\\\"\"}},{\"index\":1,\"id\":\"b\",\"function\":{\"name\":\"two\",\"arguments\":\"{}\"}}]}"),
                delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\":1}\"}}]}"),
                "[DONE]");

        LlmResponse r = call(new ArrayList<>());

        assertThat(r.toolCalls()).containsExactly(new LlmToolCall("a", "one", "{\"x\":1}"),
                new LlmToolCall("b", "two", "{}"));
    }

    @Test
    void serializesAssistantToolCallsAndToolResults() {
        sse(delta("{\"content\":\"ok\"}"), "[DONE]");

        client.chat(baseUrl(), "m", KEY, List.of(LlmMessage.user("q"),
                LlmMessage.assistant(null, List.of(new LlmToolCall("c1", "t", "{}"))),
                LlmMessage.tool("c1", "result")), List.of(), t -> { });

        JsonNode msgs = MAPPER.readTree(requestBody.get()).path("messages");
        assertThat(msgs.get(1).path("tool_calls").get(0).path("function").path("name").asString()).isEqualTo("t");
        assertThat(msgs.get(2).path("role").asString()).isEqualTo("tool");
        assertThat(msgs.get(2).path("tool_call_id").asString()).isEqualTo("c1");
    }

    @Test
    void mapsHttpStatusToErrorCodes() {
        record Case(int status, LlmException.Code code) { }
        for (Case c : List.of(new Case(401, LlmException.Code.AUTH_FAILED), new Case(403, LlmException.Code.AUTH_FAILED),
                new Case(429, LlmException.Code.RATE_LIMITED), new Case(500, LlmException.Code.BAD_RESPONSE),
                new Case(503, LlmException.Code.BAD_RESPONSE))) {
            respond(c.status(), "application/json", "{\"error\":\"" + KEY + "\"}");
            assertThatThrownBy(() -> call(new ArrayList<>()))
                    .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo(c.code()));
        }
    }

    @Test
    void rejectedRequestCarriesHttpStatusButNoProviderText() {
        for (int status : new int[]{400, 404}) {
            respond(status, "application/json", "{\"error\":\"model not found " + KEY + "\"}");
            assertThatThrownBy(() -> call(new ArrayList<>()))
                    .isInstanceOfSatisfying(LlmException.class, e -> {
                        assertThat(e.code()).isEqualTo(LlmException.Code.BAD_RESPONSE);
                        assertThat(e.httpStatus()).isEqualTo(status);
                        assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("model not found");
                    });
        }
    }

    @Test
    void rateLimitCarriesRetryAfterFromHeader() {
        record Case(String header, java.time.Duration expected) { }
        for (Case c : java.util.Arrays.asList(new Case("7", java.time.Duration.ofSeconds(7)),
                new Case("1.5", java.time.Duration.ofMillis(1500)), new Case(null, null), new Case("abc", null),
                new Case("-3", null), new Case("", null), new Case("Wed, 21 Oct 2026 07:28:00 GMT", null))) {
            retryAfterHeader = c.header();
            respond(429, "application/json", "{\"error\":\"" + KEY + "\"}");
            assertThatThrownBy(() -> call(new ArrayList<>()))
                    .isInstanceOfSatisfying(LlmException.class, e -> {
                        assertThat(e.code()).isEqualTo(LlmException.Code.RATE_LIMITED);
                        assertThat(e.retryAfter()).as("header %s", c.header()).isEqualTo(c.expected());
                        assertThat(e.getMessage()).doesNotContain(KEY);
                    });
        }
    }

    @Test
    void retryAfterIsIgnoredOnNon429() {
        retryAfterHeader = "7";
        respond(500, "application/json", "{}");
        assertThatThrownBy(() -> call(new ArrayList<>()))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryAfter()).isNull());
    }

    @Test
    void timesOutWhenProviderStallsMidStream() {
        handler = ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            os.write(("data: " + delta("{\"content\":\"a\"}") + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            sleep(3000);
            ex.close();
        };
        List<String> tokens = new ArrayList<>();

        assertThatThrownBy(() -> call(tokens)).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.code()).isEqualTo(LlmException.Code.TIMEOUT));
        assertThat(tokens).containsExactly("a");
    }

    @Test
    void timesOutWhenNoResponseHeaders() {
        handler = (HttpExchange ex) -> {
            sleep(2000);
            ex.close();
        };

        assertThatThrownBy(() -> call(new ArrayList<>())).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.code()).isEqualTo(LlmException.Code.TIMEOUT));
    }

    @Test
    void reportsUnreachableWhenNothingListens() {
        int port = server.getAddress().getPort();
        server.stop(0);

        assertThatThrownBy(() -> client.chat("http://127.0.0.1:" + port + "/v1", "m", KEY,
                List.of(LlmMessage.user("x")), List.of(), t -> { }))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.code()).isEqualTo(LlmException.Code.UNREACHABLE));
    }

    @Test
    void rejectsMalformedChunk() {
        sse(delta("{\"content\":\"a\"}"), "{not json");

        assertThatThrownBy(() -> call(new ArrayList<>())).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.code()).isEqualTo(LlmException.Code.BAD_RESPONSE));
    }

    @Test
    void rejectsEmptyStreamAndIncompleteToolCall() {
        sse("[DONE]");
        assertThatThrownBy(() -> call(new ArrayList<>())).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.code()).isEqualTo(LlmException.Code.BAD_RESPONSE));

        sse(delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{}\"}}]}"), "[DONE]");
        assertThatThrownBy(() -> call(new ArrayList<>())).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.code()).isEqualTo(LlmException.Code.BAD_RESPONSE));
    }

    @Test
    void apiKeyNeverAppearsInLogsOrExceptionMessages(CapturedOutput output) {
        List<String> messages = new ArrayList<>();
        for (int status : new int[]{400, 401, 404, 429, 500}) {
            respond(status, "application/json", "{\"error\":\"bad key " + KEY + "\"}");
            try {
                call(new ArrayList<>());
            } catch (LlmException e) {
                messages.add(e.getMessage());
            }
        }
        sse("{" + KEY);
        try {
            call(new ArrayList<>());
        } catch (LlmException e) {
            messages.add(e.getMessage());
        }

        assertThat(messages).hasSize(6).noneMatch(m -> m.contains(KEY));
        assertThat(output.getAll()).doesNotContain(KEY).doesNotContain("Bearer");
    }

    @Test
    void providerErrorBodyIsLoggedAtDebugWithKeyRedactedAndCapped(CapturedOutput output) {
        respond(429, "application/json", "{\"error\":\"rate detail " + KEY + " HEAD-MARK "
                + "x".repeat(5000) + " TAIL-MARK\"}");

        assertThatThrownBy(() -> call(new ArrayList<>())).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.getMessage()).isEqualTo("LLM provider rate limit reached"));
        assertThat(output.getAll())
                .contains("LLM provider answered HTTP 429")
                .contains("rate detail ***")
                .contains("HEAD-MARK")
                .doesNotContain(KEY)
                .doesNotContain("x".repeat(2001))
                .doesNotContain("TAIL-MARK");
    }

    private ch.qos.logback.classic.Logger logger;
    private ch.qos.logback.classic.Level previousLevel;

    @BeforeEach
    void enableDebugLogging() {
        logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
        previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
    }

    @AfterEach
    void restoreLogLevel() {
        logger.setLevel(previousLevel);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static EgressPolicy policy(boolean allowPrivate) {
        return new EgressPolicy(new EgressProperties(allowPrivate));
    }

    @Test
    void internalBaseUrlIsRefusedByDefaultAndTheKeyIsNeverSent() {
        respond(200, "text/event-stream", "data: [DONE]\n\n");
        OpenAiCompatibleLlmClient denying = new OpenAiCompatibleLlmClient(
                new LlmProperties(Duration.ofSeconds(2), Duration.ofMillis(500)), MAPPER, policy(false));

        assertThatThrownBy(() -> denying.chat(baseUrl(), "m", KEY, List.of(LlmMessage.user("hi")), List.of(), t -> { }))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.code()).isEqualTo(LlmException.Code.UNREACHABLE));
        assertThat(requestBody.get()).isNull();
    }

    @Test
    void metadataBaseUrlIsRefusedEvenWhenPrivateRangesAreAllowed() {
        assertThatThrownBy(() -> client.chat("http://169.254.169.254/v1", "m", KEY, List.of(LlmMessage.user("hi")),
                List.of(), t -> { }))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.code()).isEqualTo(LlmException.Code.UNREACHABLE));
    }
}
