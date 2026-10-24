package dev.bethge.shipir.ai.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
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
class GeminiLlmClientTest {

    private static final String KEY = "AIza-secret-key-123456";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private HttpServer server;
    private final AtomicReference<String> keyHeader = new AtomicReference<>();
    private final AtomicReference<String> requestUri = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile HttpHandler handler;
    private GeminiLlmClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models", ex -> {
            keyHeader.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            requestUri.set(ex.getRequestURI().toString());
            requestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            handler.handle(ex);
        });
        server.start();
        client = new GeminiLlmClient(new LlmProperties(Duration.ofSeconds(2), Duration.ofMillis(500)), MAPPER);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
    }

    private void respond(int status, String contentType, String body) {
        handler = ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        };
    }

    private void sse(String... dataLines) {
        StringBuilder sb = new StringBuilder();
        for (String d : dataLines) {
            sb.append("data: ").append(d).append("\r\n\r\n");
        }
        respond(200, "text/event-stream", sb.toString());
    }

    private static String textChunk(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + text + "\"}]}}]}";
    }

    private LlmResponse chat(List<LlmMessage> messages, List<LlmToolDefinition> tools, List<String> tokens) {
        return client.chat(baseUrl(), "gemini-2.5-flash", KEY, messages, tools, tokens::add);
    }

    private JsonNode sentBody() {
        return MAPPER.readTree(requestBody.get());
    }

    @Test
    void usesHttp11Only() {
        assertThat(client.httpVersion()).isEqualTo(java.net.http.HttpClient.Version.HTTP_1_1);
    }

    @Test
    void streamsTextTokensAndSendsKeyOnlyInHeader() {
        sse(textChunk("Hel"), textChunk("lo"));
        List<String> tokens = new ArrayList<>();

        LlmResponse response = chat(List.of(LlmMessage.user("hi")), List.of(), tokens);

        assertThat(tokens).containsExactly("Hel", "lo");
        assertThat(response.content()).isEqualTo("Hello");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(keyHeader.get()).isEqualTo(KEY);
        assertThat(requestUri.get()).isEqualTo("/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse")
                .doesNotContain(KEY);
        assertThat(requestBody.get()).doesNotContain(KEY);
    }

    @Test
    void returnsSingleAndMultipleToolCalls() {
        sse("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Checking\"},"
                        + "{\"functionCall\":{\"name\":\"list_hosts\",\"args\":{\"a\":1}}}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"list_containers\"}}]}}]}");

        LlmResponse response = chat(List.of(LlmMessage.user("hi")), List.of(), new ArrayList<>());

        assertThat(response.content()).isEqualTo("Checking");
        assertThat(response.toolCalls()).hasSize(2);
        assertThat(response.toolCalls().get(0).name()).isEqualTo("list_hosts");
        assertThat(response.toolCalls().get(0).argumentsJson()).isEqualTo("{\"a\":1}");
        assertThat(response.toolCalls().get(1).name()).isEqualTo("list_containers");
        assertThat(response.toolCalls().get(1).argumentsJson()).isEqualTo("{}");
        assertThat(response.toolCalls().get(0).id()).isNotEqualTo(response.toolCalls().get(1).id());
    }

    @Test
    void requestUsesGeminiShapeAndRoundTripsToolResults() {
        sse(textChunk("ok"));
        LlmToolCall c1 = new LlmToolCall("id-1", "list_hosts", "{\"x\":\"y\"}");
        LlmToolCall c2 = new LlmToolCall("id-2", "list_containers", "{}");

        chat(List.of(
                LlmMessage.system("be brief"),
                LlmMessage.user("hi"),
                LlmMessage.assistant("", List.of(c1, c2)),
                LlmMessage.tool("id-1", "hosts-result"),
                LlmMessage.tool("id-2", "containers-result"),
                LlmMessage.assistant("done", null),
                LlmMessage.user("thanks")), List.of(), new ArrayList<>());

        JsonNode body = sentBody();
        assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asString()).isEqualTo("be brief");
        JsonNode contents = body.path("contents");
        assertThat(contents).hasSize(5);
        assertThat(contents.path(0).path("role").asString()).isEqualTo("user");
        assertThat(contents.path(1).path("role").asString()).isEqualTo("model");
        JsonNode calls = contents.path(1).path("parts");
        assertThat(calls).hasSize(2);
        assertThat(calls.path(0).path("functionCall").path("name").asString()).isEqualTo("list_hosts");
        assertThat(calls.path(0).path("functionCall").path("args").path("x").asString()).isEqualTo("y");
        JsonNode results = contents.path(2).path("parts");
        assertThat(contents.path(2).path("role").asString()).isEqualTo("user");
        assertThat(results).hasSize(2);
        assertThat(results.path(0).path("functionResponse").path("name").asString()).isEqualTo("list_hosts");
        assertThat(results.path(0).path("functionResponse").path("response").path("result").asString())
                .isEqualTo("hosts-result");
        assertThat(results.path(1).path("functionResponse").path("name").asString()).isEqualTo("list_containers");
        assertThat(contents.path(3).path("role").asString()).isEqualTo("model");
        assertThat(contents.path(4).path("parts").path(0).path("text").asString()).isEqualTo("thanks");
        assertThat(requestBody.get()).doesNotContain("\"_tool\"").doesNotContain("\"messages\"");
    }

    @Test
    void toolsBecomeFunctionDeclarationsWithoutUnsupportedKeywords() {
        sse(textChunk("ok"));
        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "$schema", "http://json-schema.org/draft-07/schema#",
                "required", List.of("name"),
                "properties", Map.of(
                        "name", Map.of("type", "string", "format", "uuid", "description", "n"),
                        "tags", Map.of("type", "array", "items", Map.of("type", "string", "format", "x")),
                        "opt", Map.of("type", List.of("integer", "null"), "minimum", 1,
                                "additionalProperties", false)));

        chat(List.of(LlmMessage.user("hi")), List.of(
                new LlmToolDefinition("t", "desc", schema),
                new LlmToolDefinition("noargs", "d", Map.of("type", "object", "properties", Map.of()))),
                new ArrayList<>());

        JsonNode decls = sentBody().path("tools").path(0).path("functionDeclarations");
        assertThat(decls).hasSize(2);
        JsonNode params = decls.path(0).path("parameters");
        assertThat(decls.path(0).path("name").asString()).isEqualTo("t");
        assertThat(params.path("type").asString()).isEqualTo("object");
        assertThat(params.path("required").path(0).asString()).isEqualTo("name");
        assertThat(params.path("properties").path("opt").path("type").asString()).isEqualTo("integer");
        assertThat(params.path("properties").path("opt").path("nullable").asBoolean()).isTrue();
        assertThat(params.path("properties").path("opt").path("minimum").asInt()).isEqualTo(1);
        assertThat(decls.path(1).has("parameters")).isFalse();
        assertThat(requestBody.get()).doesNotContain("additionalProperties").doesNotContain("\"format\"")
                .doesNotContain("$schema");
    }

    @Test
    void skipsThoughtParts() {
        sse("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"thinking\",\"thought\":true},{\"text\":\"answer\"}]}}]}");
        List<String> tokens = new ArrayList<>();

        assertThat(chat(List.of(LlmMessage.user("hi")), List.of(), tokens).content()).isEqualTo("answer");
        assertThat(tokens).containsExactly("answer");
    }

    @Test
    void mapsStatusCodes() {
        for (int[] c : new int[][]{{401, 0}, {403, 0}, {429, 1}, {500, 2}, {400, 2}}) {
            respond(c[0], "application/json", "{\"error\":{\"message\":\"provider body " + KEY + "\"}}");
            LlmException ex = catchLlm();
            LlmException.Code expected = c[1] == 0 ? LlmException.Code.AUTH_FAILED
                    : c[1] == 1 ? LlmException.Code.RATE_LIMITED : LlmException.Code.BAD_RESPONSE;
            assertThat(ex.code()).as("status %d", c[0]).isEqualTo(expected);
            assertThat(ex.getMessage()).doesNotContain(KEY).doesNotContain("provider body");
        }
    }

    private LlmException catchLlm() {
        try {
            chat(List.of(LlmMessage.user("hi")), List.of(), new ArrayList<>());
        } catch (LlmException e) {
            return e;
        }
        throw new AssertionError("expected LlmException");
    }

    @Test
    void malformedChunkIsBadResponse() {
        sse(textChunk("a"), "{not json");

        assertThat(catchLlm().code()).isEqualTo(LlmException.Code.BAD_RESPONSE);
    }

    @Test
    void errorChunkAndEmptyStreamAreBadResponse() {
        sse("{\"error\":{\"message\":\"boom\"}}");
        assertThat(catchLlm().code()).isEqualTo(LlmException.Code.BAD_RESPONSE);

        respond(200, "text/event-stream", "");
        assertThat(catchLlm().code()).isEqualTo(LlmException.Code.BAD_RESPONSE);
    }

    @Test
    void stalledStreamTimesOut() {
        handler = ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            os.write(("data: " + textChunk("a") + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        };

        assertThat(catchLlm().code()).isEqualTo(LlmException.Code.TIMEOUT);
    }

    @Test
    void unreachableServerIsUnreachable() {
        server.stop(0);

        assertThat(catchLlm().code()).isEqualTo(LlmException.Code.UNREACHABLE);
    }

    @Test
    void keyNeverAppearsInLogsOrMessages(CapturedOutput output) {
        respond(401, "application/json", "{\"error\":\"" + KEY + "\"}");

        LlmException ex = catchLlm();

        assertThat(ex.getMessage()).doesNotContain(KEY);
        assertThat(output.getAll()).doesNotContain(KEY);
    }
}
