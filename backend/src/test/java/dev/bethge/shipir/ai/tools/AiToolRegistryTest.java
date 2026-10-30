package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.compose.DeploymentException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.bethge.shipir.host.HostException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
        return Map.of("type", "object", "required", List.of("hostId"),
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

    private static AiToolRegistry registryWithStoredEnv(List<String> storedValues, AiTool tool) {
        return new AiToolRegistry(List.of(tool), MAPPER, AiToolRegistry.DEFAULT_MAX_RESULT_CHARS, Clock.systemUTC(),
                args -> storedValues);
    }

    private static AiTool invalidCompose(String... details) {
        return tool("t", Role.VIEWER, false, (a, c) -> {
            throw new DeploymentException(HttpStatus.BAD_REQUEST, "INVALID_COMPOSE", "The compose file is invalid",
                    List.of(details));
        });
    }

    private static String args() {
        return "{\"hostId\":\"" + UUID.randomUUID() + "\"}";
    }

    @Test
    void invalidComposeDetailsKeepTheMessageButRedactStoredEnvValues() {
        AiToolRegistry registry = registryWithStoredEnv(List.of("hunter2", "notaport"), invalidCompose(
                "services.web.mem_limit: parsing hunter2: invalid syntax",
                "invalid hostPort: notaport"));

        ToolResult result = registry.execute("t", args(), ctx(Role.VIEWER));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("INVALID_COMPOSE", "The compose file is invalid",
                "services.web.mem_limit: parsing ***: invalid syntax", "invalid hostPort: ***")
                .doesNotContain("hunter2").doesNotContain("notaport");
    }

    @Test
    void invalidComposeDetailsRedactEnvPassedInlineInTheSameCall() {
        Map<String, Object> withEnv = Map.of("type", "object", "required", List.of("hostId"), "properties",
                Map.of("hostId", Map.of("type", "string", "format", "uuid"), "env", Map.of("type", "string")));
        AiToolRegistry registry = registry(new FunctionTool("t", "d", withEnv, Role.VIEWER, false, (a, c) -> {
            throw new DeploymentException(HttpStatus.BAD_REQUEST, "INVALID_COMPOSE", "The compose file is invalid",
                    List.of("parsing s3cr3t-value: invalid syntax"));
        }, v -> MAPPER.writeValueAsString(v)));
        String json = "{\"hostId\":\"" + UUID.randomUUID() + "\",\"env\":\"MEM=s3cr3t-value\\nDEBUG=1\"}";

        ToolResult result = registry.execute("t", json, ctx(Role.VIEWER));

        assertThat(result.content()).contains("parsing ***: invalid syntax").doesNotContain("s3cr3t-value");
    }

    @Test
    void failsClosedWhenTheStoredEnvCannotBeRead() {
        AiToolRegistry registry = new AiToolRegistry(List.of(invalidCompose("parsing hunter2: invalid syntax")), MAPPER,
                AiToolRegistry.DEFAULT_MAX_RESULT_CHARS, Clock.systemUTC(), a -> {
                    throw new DeploymentEnvValues.Unavailable(new IllegalStateException("DECRYPTION_FAILED"));
                });

        ToolResult result = registry.execute("t", args(), ctx(Role.VIEWER));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("INVALID_COMPOSE", "The compose file is invalid")
                .doesNotContain("hunter2").doesNotContain("parsing");
    }

    @Test
    void failsClosedWhenRedactionFailsForAnyOtherReason() {
        java.util.AbstractCollection<String> broken = new java.util.AbstractCollection<>() {
            @Override public java.util.Iterator<String> iterator() { throw new IllegalStateException("boom"); }
            @Override public int size() { return 1; }
        };
        AiToolRegistry registry = new AiToolRegistry(List.of(invalidCompose("parsing hunter2: invalid syntax")), MAPPER,
                AiToolRegistry.DEFAULT_MAX_RESULT_CHARS, Clock.systemUTC(), a -> broken);

        ToolResult result = registry.execute("t", args(), ctx(Role.VIEWER));

        assertThat(result.content()).contains("INVALID_COMPOSE", "The compose file is invalid")
                .doesNotContain("hunter2").doesNotContain("parsing");
    }

    @Test
    void callNamingNoDeploymentAndNoInlineEnvKeepsTheDetails() {
        // the real lookup returns nothing without hostId/deploymentId: there is no env to protect
        AiToolRegistry registry = registry(invalidCompose("services.web.image: must be a string"));

        ToolResult result = registry.execute("t", args(), ctx(Role.VIEWER));

        assertThat(result.content()).contains("services.web.image: must be a string");
    }

    @Test
    void nonSecretDetailsAreUnchanged() {
        AiToolRegistry registry = registryWithStoredEnv(List.of("hunter2"),
                invalidCompose("services.web.image: must be a string", "line 4: unknown key"));

        ToolResult result = registry.execute("t", args(), ctx(Role.VIEWER));

        assertThat(result.content()).contains("services.web.image: must be a string", "line 4: unknown key")
                .doesNotContain("***");
    }

    @Test
    void envValuesUnderFourCharactersAreNotRedacted() {
        AiToolRegistry registry = registryWithStoredEnv(List.of("abc", "1", "", "hunter2"),
                invalidCompose("abc is invalid in service 1", "parsing hunter2"));

        ToolResult result = registry.execute("t", args(), ctx(Role.VIEWER));

        assertThat(result.content()).contains("abc is invalid in service 1", "parsing ***")
                .doesNotContain("hunter2");
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
    void oversizedListResultsStayValidJsonAndSayHowManyItemsWereOmitted() throws Exception {
        List<Map<String, String>> containers = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            containers.add(Map.of("id", "c" + i, "name", "container-" + i, "image", "x".repeat(100)));
        }
        AiToolRegistry registry = new AiToolRegistry(List.of(
                tool("list", Role.VIEWER, false, (a, c) -> containers)), MAPPER, 1000);

        String content = registry.execute("list", "{\"hostId\":\"" + UUID.randomUUID() + "\"}", ctx(Role.VIEWER)).content();

        tools.jackson.databind.JsonNode node = MAPPER.readTree(content);
        int kept = node.get("items").size();
        assertThat(content.length()).isLessThanOrEqualTo(1000);
        assertThat(kept).isBetween(1, 29);
        assertThat(node.get("omittedItems").asInt()).isEqualTo(30 - kept);
        assertThat(node.get("note").asString()).contains("truncated");
        assertThat(node.get("items").get(0).get("id").asString()).isEqualTo("c0");
    }

    @Test
    void oversizedPageObjectKeepsItsFieldsAndShortensTheItemsArray() throws Exception {
        List<Map<String, String>> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            entries.add(Map.of("action", "A" + i, "details", "d".repeat(100)));
        }
        AiToolRegistry registry = new AiToolRegistry(List.of(
                tool("audit", Role.VIEWER, false, (a, c) -> Map.of("totalElements", 30, "content", entries))), MAPPER, 1000);

        String content = registry.execute("audit", "{\"hostId\":\"" + UUID.randomUUID() + "\"}", ctx(Role.VIEWER)).content();

        tools.jackson.databind.JsonNode node = MAPPER.readTree(content);
        assertThat(content.length()).isLessThanOrEqualTo(1000);
        assertThat(node.get("totalElements").asInt()).isEqualTo(30);
        assertThat(node.get("content").size()).isBetween(1, 29);
        assertThat(node.get("omittedItems").asInt()).isEqualTo(30 - node.get("content").size());
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

    /** Runs the action with the registry logger at DEBUG and returns every event it logged. */
    private static List<ILoggingEvent> logsWhile(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(AiToolRegistry.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
        return appender.list;
    }

    @Test
    void unexpectedToolFailureLogsExceptionClassAndMessageAtDebug() {
        AiToolRegistry registry = registry(tool("boom", Role.VIEWER, false, (a, c) -> {
            throw new IllegalStateException("unit-marker-message: /data/stacks/outside");
        }));

        List<ILoggingEvent> events = logsWhile(() -> registry.execute("boom", args(), ctx(Role.VIEWER)));

        assertThat(events).filteredOn(e -> e.getLevel() == Level.DEBUG)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("IllegalStateException", "unit-marker-message: /data/stacks/outside"));
    }

    @Test
    void unexpectedToolFailureNeverLogsArgumentsOrAStackTrace() {
        AiToolRegistry registry = new AiToolRegistry(List.of(new FunctionTool("boom", "desc",
                Map.of("type", "object", "properties", Map.of("note", Map.of("type", "string"))),
                Role.VIEWER, false, (a, c) -> {
                    throw new IllegalStateException("unit-marker-message");
                }, v -> MAPPER.writeValueAsString(v))), MAPPER);

        List<ILoggingEvent> events = logsWhile(() -> registry.execute("boom", "{\"note\":\"SECRET-ARG\"}", ctx(Role.VIEWER)));

        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(e -> {
            assertThat(e.getFormattedMessage()).doesNotContain("SECRET-ARG");
            assertThat(e.getArgumentArray()).allSatisfy(arg -> assertThat(String.valueOf(arg)).doesNotContain("SECRET-ARG"));
            assertThat(e.getThrowableProxy()).isNull();
        });
        assertThat(events).filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("boom", "IllegalStateException")
                        .doesNotContain("unit-marker-message"));
    }

    @Test
    void rejectsDuplicateToolNames() {
        assertThatThrownBy(() -> registry(tool("d", Role.VIEWER, false, (a, c) -> "1"),
                tool("d", Role.VIEWER, false, (a, c) -> "2")))
                .isInstanceOf(IllegalStateException.class);
    }
}
