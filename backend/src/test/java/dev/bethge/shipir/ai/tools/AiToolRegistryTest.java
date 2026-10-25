package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.compose.DeploymentException;
import dev.bethge.shipir.host.HostException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiToolRegistryTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final UUID USER = UUID.randomUUID();

    private static ToolContext ctx(Role role) {
        return new ToolContext(USER, role.name().toLowerCase(), role);
    }

    private static Map<String, Object> schema() {
        return Map.of("type", "object", "required", List.of("hostId"), "additionalProperties", false,
                "properties", Map.of(
                        "hostId", Map.of("type", "string", "format", "uuid"),
                        "size", Map.of("type", "integer", "minimum", 1, "maximum", 100),
                        "flag", Map.of("type", "boolean"),
                        "mode", Map.of("type", "string", "enum", List.of("a", "b"))));
    }

    private static AiTool tool(String name, Role role, boolean write, BiFunction<Map<String, Object>, ToolContext, Object> fn) {
        return new FunctionTool(name, "desc " + name, schema(), role, write, fn, v -> MAPPER.writeValueAsString(v));
    }

    private static AiToolRegistry registry(AiTool... tools) {
        return new AiToolRegistry(List.of(tools), MAPPER);
    }

    @Test
    void offersOnlyToolsAllowedForTheRole() {
        AiToolRegistry registry = registry(
                tool("viewer_tool", Role.VIEWER, false, (a, c) -> "v"),
                tool("operator_tool", Role.OPERATOR, true, (a, c) -> "o"),
                tool("admin_tool", Role.ADMIN, false, (a, c) -> "a"));

        assertThat(names(registry, Role.VIEWER)).containsExactly("viewer_tool");
        assertThat(names(registry, Role.OPERATOR)).containsExactly("operator_tool", "viewer_tool");
        assertThat(names(registry, Role.ADMIN)).containsExactly("admin_tool", "operator_tool", "viewer_tool");
        assertThat(registry.definitionsFor(Role.VIEWER)).singleElement()
                .satisfies(d -> {
                    assertThat(d.name()).isEqualTo("viewer_tool");
                    assertThat(d.parameters()).containsKey("properties");
                });
    }

    private static List<String> names(AiToolRegistry registry, Role role) {
        return registry.toolsFor(role).stream().map(AiTool::name).toList();
    }

    @Test
    void doesNotRunAToolAboveTheCallersRoleEvenIfTheModelNamesIt() {
        AiToolRegistry registry = registry(tool("admin_tool", Role.ADMIN, false, (a, c) -> {
            throw new AssertionError("must not run");
        }));

        ToolResult result = registry.execute("admin_tool", "{\"hostId\":\"" + UUID.randomUUID() + "\"}", ctx(Role.VIEWER));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("UNKNOWN_TOOL");
    }

    @Test
    void exposesWriteFlag() {
        AiToolRegistry registry = registry(tool("w", Role.OPERATOR, true, (a, c) -> "x"),
                tool("r", Role.VIEWER, false, (a, c) -> "x"));

        assertThat(registry.find("w", Role.OPERATOR).write()).isTrue();
        assertThat(registry.find("r", Role.OPERATOR).write()).isFalse();
        assertThat(registry.find("w", Role.VIEWER)).isNull();
    }

    @Test
    void passesValidatedArgumentsAndContextToTheTool() {
        UUID host = UUID.randomUUID();
        AiToolRegistry registry = registry(tool("t", Role.VIEWER, false,
                (a, c) -> Map.of("host", a.get("hostId"), "size", a.get("size"), "user", c.username())));

        ToolResult result = registry.execute("t", "{\"hostId\":\"" + host + "\",\"size\":5}", ctx(Role.VIEWER));

        assertThat(result.error()).isFalse();
        JsonNode json = MAPPER.readTree(result.content());
        assertThat(json.get("host").asString()).isEqualTo(host.toString());
        assertThat(json.get("size").asInt()).isEqualTo(5);
        assertThat(json.get("user").asString()).isEqualTo("viewer");
    }

    @Test
    void invalidArgumentsBecomeAnErrorResultNotAnException() {
        AiToolRegistry registry = registry(tool("t", Role.VIEWER, false, (a, c) -> {
            throw new AssertionError("must not run");
        }));
        String uuid = UUID.randomUUID().toString();

        for (String args : List.of(
                "{}",                                                    // missing required
                "{\"hostId\":\"not-a-uuid\"}",
                "{\"hostId\":123}",
                "{\"hostId\":\"" + uuid + "\",\"size\":0}",
                "{\"hostId\":\"" + uuid + "\",\"size\":101}",
                "{\"hostId\":\"" + uuid + "\",\"size\":\"5\"}",
                "{\"hostId\":\"" + uuid + "\",\"size\":1.5}",
                "{\"hostId\":\"" + uuid + "\",\"flag\":\"yes\"}",
                "{\"hostId\":\"" + uuid + "\",\"mode\":\"c\"}",
                "{\"hostId\":\"" + uuid + "\",\"extra\":1}",
                "{\"hostId\":[\"" + uuid + "\"]}",
                "[1,2]",
                "not json {")) {
            ToolResult result = registry.execute("t", args, ctx(Role.VIEWER));
            assertThat(result.error()).as(args).isTrue();
            assertThat(result.content()).as(args).contains("INVALID_ARGUMENTS");
        }
    }

    @Test
    void blankArgumentsAreAnEmptyObject() {
        AiToolRegistry registry = registry(new FunctionTool("n", "d",
                Map.of("type", "object", "properties", Map.of()), Role.VIEWER, false, (a, c) -> "ok",
                v -> MAPPER.writeValueAsString(v)));

        assertThat(registry.execute("n", "", ctx(Role.VIEWER)).error()).isFalse();
        assertThat(registry.execute("n", null, ctx(Role.VIEWER)).error()).isFalse();
    }

    @Test
    void unknownToolIsAnErrorResult() {
        ToolResult result = registry().execute("nope", "{}", ctx(Role.ADMIN));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("UNKNOWN_TOOL");
    }

    @Test
    void mapsDomainExceptionsToTheirCodeAndHidesUnexpectedOnes() {
        String args = "{\"hostId\":\"" + UUID.randomUUID() + "\"}";
        AiToolRegistry registry = registry(
                tool("host", Role.VIEWER, false, (a, c) -> {
                    // constructor is package-private in host/, so a stub stands in
                    HostException e = org.mockito.Mockito.mock(HostException.class);
                    org.mockito.Mockito.when(e.code()).thenReturn("HOST_NOT_FOUND");
                    throw e;
                }),
                tool("stack", Role.VIEWER, false, (a, c) -> {
                    throw new DeploymentException(HttpStatus.NOT_FOUND, "DEPLOYMENT_NOT_FOUND", "Stack not found");
                }),
                tool("arg", Role.VIEWER, false, (a, c) -> {
                    throw new ToolArgumentException("'from' is malformed");
                }),
                tool("boom", Role.VIEWER, false, (a, c) -> {
                    throw new IllegalStateException("jdbc:postgresql://secret-host password=hunter2");
                }));

        assertThat(registry.execute("host", args, ctx(Role.VIEWER)).content()).contains("HOST_NOT_FOUND");
        assertThat(registry.execute("stack", args, ctx(Role.VIEWER)).content()).contains("DEPLOYMENT_NOT_FOUND");
        assertThat(registry.execute("arg", args, ctx(Role.VIEWER)).content()).contains("INVALID_ARGUMENTS", "malformed");
        ToolResult boom = registry.execute("boom", args, ctx(Role.VIEWER));
        assertThat(boom.error()).isTrue();
        assertThat(boom.content()).contains("TOOL_FAILED").doesNotContain("hunter2").doesNotContain("secret-host");
    }

    @Test
    void truncatesOversizedResultsAndSaysSo() {
        AiToolRegistry registry = new AiToolRegistry(List.of(
                tool("big", Role.VIEWER, false, (a, c) -> "x".repeat(500))), MAPPER, 100);

        ToolResult result = registry.execute("big", "{\"hostId\":\"" + UUID.randomUUID() + "\"}", ctx(Role.VIEWER));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).startsWith("\"xxx").contains("truncated");
        assertThat(result.content().length()).isLessThan(400);
    }

    @Test
    void truncationNoteTellsTheModelTheResultWasCutAndHowManyCharsAreMissing() {
        AiToolRegistry registry = new AiToolRegistry(List.of(
                tool("big", Role.VIEWER, false, (a, c) -> "x".repeat(500))), MAPPER, new AiToolProperties(100), Clock.systemUTC());

        String content = registry.execute("big", "{\"hostId\":\"" + UUID.randomUUID() + "\"}", ctx(Role.VIEWER)).content();

        assertThat(content).contains("RESULT TRUNCATED", "result was cut", "narrow", "more characters omitted");
    }

    @Test
    void capComesFromTheProperties() {
        assertThat(new AiToolProperties(null).maxResultChars()).isEqualTo(4000);
        assertThat(new AiToolProperties(0).maxResultChars()).isEqualTo(4000);
        assertThat(new AiToolProperties(123).maxResultChars()).isEqualTo(123);

        AiToolRegistry custom = new AiToolRegistry(List.of(
                tool("mid", Role.VIEWER, false, (a, c) -> "y".repeat(300))), MAPPER, new AiToolProperties(1000), Clock.systemUTC());
        assertThat(custom.execute("mid", "{\"hostId\":\"" + UUID.randomUUID() + "\"}", ctx(Role.VIEWER)).content())
                .doesNotContain("truncated");
    }

    @Test
    void smallResultsAreNotTruncated() {
        AiToolRegistry registry = new AiToolRegistry(List.of(
                tool("small", Role.VIEWER, false, (a, c) -> "ok")), MAPPER, 100);

        assertThat(registry.execute("small", "{\"hostId\":\"" + UUID.randomUUID() + "\"}", ctx(Role.VIEWER)).content())
                .isEqualTo("\"ok\"");
    }

    @Test
    void rejectsDuplicateToolNames() {
        assertThatThrownBy(() -> registry(tool("d", Role.VIEWER, false, (a, c) -> "1"),
                tool("d", Role.VIEWER, false, (a, c) -> "2")))
                .isInstanceOf(IllegalStateException.class);
    }
}
