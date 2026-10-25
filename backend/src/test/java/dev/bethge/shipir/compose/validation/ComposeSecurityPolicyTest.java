package dev.bethge.shipir.compose.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.params.provider.MethodSource;
import dev.bethge.shipir.compose.execution.ComposeExecutionException;

class ComposeSecurityPolicyTest {

    @TempDir
    Path dir;

    private List<String> check(String json) {
        return ComposeSecurityPolicy.violationsOf(JsonMapper.builder().build().readTree(json), dir);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "privileged|true|privileged",
            "cap_add|[\"SYS_ADMIN\"]|cap_add",
            "pid|\"host\"|pid host",
            "ipc|\"host\"|ipc host",
            "network_mode|\"host\"|network_mode host",
            "devices|[\"/dev/sda:/dev/sda\"]|devices",
            "security_opt|[\"seccomp:unconfined\"]|security_opt",
            "userns_mode|\"host\"|userns_mode"})
    void deniesHostLevelServiceSetting(String key, String value, String expected) {
        assertThat(check("{\"services\":{\"x\":{\"image\":\"alpine\",\"%s\":%s}}}".formatted(key, value)))
                .containsExactly("x: " + expected);
    }

    @Test
    void deniesAbsoluteHostBindMountAndDockerSocket() {
        String json = """
                {"services":{"x":{"volumes":[
                  {"type":"bind","source":"/","target":"/host"},
                  {"type":"bind","source":"/var/run/docker.sock","target":"/var/run/docker.sock"}]}}}""";
        assertThat(check(json)).containsExactly("x: bind mount /", "x: docker.sock mount");
    }

    @Test
    void deniesBindMountEscapingTheDeploymentDirectory() {
        assertThat(check("{\"services\":{\"x\":{\"volumes\":[{\"type\":\"bind\",\"source\":\"%s/../../etc\"}]}}}"
                .formatted(dir))).hasSize(1);
    }

    @Test
    void deniesSymlinkInsideDirectoryPointingOut() throws IOException {
        Files.createSymbolicLink(dir.resolve("escape"), Path.of("/etc"));
        assertThat(check("{\"services\":{\"x\":{\"volumes\":[{\"type\":\"bind\",\"source\":\"%s/escape\"}]}}}"
                .formatted(dir))).hasSize(1);
    }

    @Test
    void deniesNamedVolumeThatIsABindMountInDisguise() {
        String json = "{\"volumes\":{\"v\":{\"driver\":\"local\",\"driver_opts\":{\"type\":\"none\",\"o\":\"bind\",\"device\":\"/\"}}}}";
        assertThat(check(json)).containsExactly("volume v: bind mount via driver_opts");
    }

    @Test
    void allowsNamedVolumesRelativePathsInsideTheDirectoryAndHarmlessSettings() {
        String json = """
                {"services":{"x":{"image":"nginx","privileged":false,"cap_add":[],"pid":"service:y","network_mode":"bridge",
                  "volumes":[{"type":"volume","source":"data","target":"/data"},
                             {"type":"bind","source":"%s/repo/conf","target":"/conf"},
                             {"type":"tmpfs","target":"/tmp"}]}},
                 "volumes":{"data":{"driver":"local"}}}""".formatted(dir);
        assertThat(check(json)).isEmpty();
    }

    @Test
    void parsesJsonAfterWarningLines() {
        JsonNode node = ComposeSecurityPolicy.parse(List.of("WARN[0000] the attribute `version` is obsolete", "{",
                "  \"name\": \"p\",", "  \"services\": {}", "}"));
        assertThat(node.has("services")).isTrue();
    }

    @Test
    void decoyObjectLineBeforeTheRealConfigDoesNotHideIt() {
        JsonNode node = ComposeSecurityPolicy.parse(List.of("{}", "{\"name\":\"p\",\"services\":{\"x\":{\"privileged\":true}}}"));
        assertThat(node.path("services").has("x")).isTrue();
    }

    @ParameterizedTest
    @MethodSource("unusableOutputs")
    void failsClosedOnOutputThatIsNotACompleteComposeConfig(List<String> output) {
        assertThatThrownBy(() -> ComposeSecurityPolicy.parse(output)).isInstanceOf(ComposeExecutionException.class);
    }

    static java.util.stream.Stream<List<String>> unusableOutputs() {
        return java.util.stream.Stream.of(
                List.of(), List.of(""), List.of("[]"), List.of("{}"), List.of("WARN only"), List.of("{not json"),
                List.of("{\"services\":{}}"), List.of("{\"name\":\"p\",\"services\":[]}"),
                List.of("{\"name\":\"p\",\"services\":{}}", "trailing garbage"),
                List.of("{}", "{\"name\":\"p\"} x"));
    }

    @Test
    void deniesSymlinkLoopBecauseItCannotBeResolved() throws IOException {
        Files.createSymbolicLink(dir.resolve("loop"), dir.resolve("loop"));
        assertThat(check("{\"services\":{\"x\":{\"volumes\":[{\"type\":\"bind\",\"source\":\"%s/loop/data\"}]}}}"
                .formatted(dir))).hasSize(1);
    }
}
