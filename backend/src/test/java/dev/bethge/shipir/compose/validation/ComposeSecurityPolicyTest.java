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
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "use_api_socket|true|use_api_socket",
            "uts|\"host\"|uts host",
            "cgroup|\"host\"|cgroup host",
            "cgroup_parent|\"foo\"|cgroup_parent",
            "device_cgroup_rules|[\"c 1:3 mr\"]|device_cgroup_rules",
            "gpus|[{\"count\":-1}]|gpus",
            "volumes_from|[\"b\"]|volumes_from",
            "network_mode|\"container:abc\"|network_mode container:",
            "pid|\"container:abc\"|pid container:",
            "ipc|\"container:abc\"|ipc container:",
            "deploy|{\"resources\":{\"reservations\":{\"devices\":[{\"capabilities\":[\"gpu\"]}]}}}|deploy reservations devices"})
    void deniesFurtherHostLevelServiceSetting(String key, String value, String expected) {
        assertThat(check("{\"services\":{\"x\":{\"image\":\"alpine\",\"%s\":%s}}}".formatted(key, value)))
                .containsExactly("x: " + expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"labels\":{\"com.docker.compose.project\":\"other\"}}|labels com.docker.compose.project",
            "{\"labels\":{\"com.docker.compose.service\":\"db\"}}|labels com.docker.compose.service",
            "{\"labels\":{\"COM.Docker.Compose.Project\":\"other\"}}|labels COM.Docker.Compose.Project",
            "{\"labels\":[\"com.docker.compose.project=other\"]}|labels com.docker.compose.project",
            "{\"deploy\":{\"labels\":{\"com.docker.compose.project\":\"other\"}}}|deploy.labels com.docker.compose.project",
            "{\"deploy\":{\"labels\":[\"com.docker.compose.oneoff=True\"]}}|deploy.labels com.docker.compose.oneoff"})
    void deniesUserSetComposeLabels(String caseDef) {
        String[] parts = caseDef.split("\\|");
        String service = parts[0].substring(0, parts[0].length() - 1) + ",\"image\":\"alpine\"}";
        assertThat(check("{\"services\":{\"x\":" + service + "}}")).containsExactly("x: " + parts[1]);
    }

    @Test
    void allowsOtherLabelsIncludingLookalikePrefixes() {
        assertThat(check("""
                {"services":{"x":{"image":"alpine","labels":{"traefik.enable":"true","com.docker.composer":"1",
                "com.example.com.docker.compose.project":"1"},"deploy":{"labels":{"app":"x"}}}}}""")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "privileged|true|build privileged",
            "network|\"host\"|build network host",
            "entitlements|[\"network.host\"]|build entitlements",
            "ssh|[\"default\"]|build ssh",
            "ssh|{\"default\":null}|build ssh",
            "ssh|{\"k\":\"/home/u/.ssh/id\"}|build ssh"})
    void deniesBuildSetting(String key, String value, String expected) {
        assertThat(check("{\"services\":{\"x\":{\"build\":{\"context\":\"%s\",\"%s\":%s}}}}".formatted(dir, key, value)))
                .containsExactly("x: " + expected);
    }

    @Test
    void deniesBuildContextAdditionalContextAndDockerfileOutsideTheDirectory() {
        String json = """
                {"services":{"x":{"build":{"context":"/etc","dockerfile":"/etc/passwd",
                  "additional_contexts":{"a":"/etc","b":"https://example.com/r.git"}}}}}""";
        assertThat(check(json)).containsExactly("x: build context /etc", "x: build dockerfile /etc/passwd",
                "x: build additional_contexts a /etc", "x: build additional_contexts b https://example.com/r.git");
    }

    @Test
    void deniesRelativeDockerfileEscapingTheDirectory() {
        String json = "{\"services\":{\"x\":{\"build\":{\"context\":\"%s/sub\",\"dockerfile\":\"../../Dockerfile\"}}}}".formatted(dir);
        assertThat(check(json)).containsExactly("x: build dockerfile ../../Dockerfile");
    }

    @Test
    void allowsBuildInsideTheDirectoryAndSysctlsAndGroupAdd() {
        String json = """
                {"services":{"x":{"sysctls":{"net.core.somaxconn":"1"},"group_add":["docker"],"network_mode":"service:y",
                  "build":{"context":"%1$s/sub","dockerfile":"Dockerfile","network":"none",
                    "additional_contexts":{"a":"%1$s/other","b":"docker-image://alpine","c":"service:y"}}}}}""".formatted(dir);
        assertThat(check(json)).isEmpty();
    }

    @Test
    void deniesSymlinkedBuildContextPointingOut() throws IOException {
        Files.createSymbolicLink(dir.resolve("escape"), Path.of("/etc"));
        assertThat(check("{\"services\":{\"x\":{\"build\":{\"context\":\"%s/escape\"}}}}".formatted(dir)))
                .containsExactly("x: build context " + dir + "/escape");
    }

    @Test
    void deniesRelativeDockerfileThatEscapesThroughSymlinkBeforeDotDot() throws IOException {
        Path outside = Files.createDirectories(Files.createTempDirectory("outside").resolve("deep"));
        Files.createSymbolicLink(dir.resolve("link"), outside);
        // link/../Dockerfile is outside/Dockerfile for Docker, not dir/Dockerfile as lexical normalization suggests
        String json = "{\"services\":{\"x\":{\"build\":{\"context\":\"%s\",\"dockerfile\":\"link/../Dockerfile\"}}}}".formatted(dir);
        assertThat(check(json)).containsExactly("x: build dockerfile link/../Dockerfile");
    }

    @Test
    void allowsRelativeDockerfileWithDotDotInsideRealSubdirectory() throws IOException {
        Files.createDirectories(dir.resolve("sub"));
        String json = "{\"services\":{\"x\":{\"build\":{\"context\":\"%s\",\"dockerfile\":\"sub/../Dockerfile\"}}}}".formatted(dir);
        assertThat(check(json)).isEmpty();
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

    @ParameterizedTest
    @ValueSource(strings = {"nfs", "nfs4", "cifs", "smbfs", "NFS", " cifs "})
    void deniesNamedVolumeOnANetworkFilesystem(String type) {
        String json = "{\"volumes\":{\"v\":{\"driver\":\"local\",\"driver_opts\":{\"type\":\"" + type
                + "\",\"o\":\"addr=10.0.0.5,rw\",\"device\":\":/export\"}}}}";
        assertThat(check(json)).containsExactly("volume v: network filesystem via driver_opts");
    }

    @ParameterizedTest
    @ValueSource(strings = {"fuse.sshfs", "fuse.glusterfs", "ceph", "glusterfs", "sshfs", "9p", "ext4", "overlay", "davfs", "nfs4 ", "x"})
    void deniesNamedVolumeWithAnyOtherFilesystemType(String type) {
        String json = "{\"volumes\":{\"v\":{\"driver\":\"local\",\"driver_opts\":{\"type\":\"" + type
                + "\",\"device\":\"host:/x\"}}}}";
        assertThat(check(json)).hasSize(1).first().asString().startsWith("volume v: ");
        assertThat(check(json.replace("\"driver\":\"local\",", ""))).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rexray/ebs", "vieux/sshfs", "local-persist", "Local", "tmpfs"})
    void deniesNamedVolumeWithNonLocalDriver(String driver) {
        String json = "{\"volumes\":{\"v\":{\"driver\":\"" + driver + "\"}}}";
        assertThat(check(json)).containsExactly("volume v: driver " + driver + " not allowed (only local)");
    }

    @Test
    void allowsBindViaDriverOptsOnlyInsideTheDeploymentDirectory() throws IOException {
        Files.createDirectories(dir.resolve("data"));
        String inside = "{\"volumes\":{\"v\":{\"driver_opts\":{\"type\":\"none\",\"o\":\"bind\",\"device\":\"%s/data\"}}}}"
                .formatted(dir);
        assertThat(check(inside)).isEmpty();
        String outside = inside.replace(dir + "/data", "/etc");
        assertThat(check(outside)).containsExactly("volume v: bind mount via driver_opts");
        String noDevice = "{\"volumes\":{\"v\":{\"driver_opts\":{\"type\":\"none\",\"o\":\"bind\"}}}}";
        assertThat(check(noDevice)).containsExactly("volume v: bind mount via driver_opts");
        String notBind = "{\"volumes\":{\"v\":{\"driver_opts\":{\"type\":\"none\",\"device\":\"%s/data\"}}}}".formatted(dir);
        assertThat(check(notBind)).containsExactly("volume v: bind mount via driver_opts");
    }

    @Test
    void deniesBindViaDriverOptsThroughSymlinkOutOfTheDirectory() throws IOException {
        Files.createSymbolicLink(dir.resolve("escape"), Path.of("/etc"));
        String json = "{\"volumes\":{\"v\":{\"driver_opts\":{\"type\":\"none\",\"o\":\"rbind\",\"device\":\"%s/escape\"}}}}"
                .formatted(dir);
        assertThat(check(json)).containsExactly("volume v: bind mount via driver_opts");
    }

    @Test
    void allowsLocalAndUnsetDriverWithoutTypeAndTmpfs() {
        String json = "{\"volumes\":{\"a\":{},\"b\":{\"driver\":\"local\",\"driver_opts\":{}},"
                + "\"c\":{\"driver\":\"local\",\"driver_opts\":{\"type\":\"TMPFS\",\"device\":\"tmpfs\"}},"
                + "\"d\":{\"external\":true,\"name\":\"shared\"}}}";
        assertThat(check(json)).isEmpty();
    }

    @Test
    void allowsNamedVolumesWithHarmlessDriverOptsAndWithout() {
        String json = "{\"volumes\":{\"a\":{\"driver\":\"local\"},\"b\":{\"driver\":\"local\",\"driver_opts\":{\"type\":\"tmpfs\",\"device\":\"tmpfs\",\"o\":\"size=100m\"}}}}";
        assertThat(check(json)).isEmpty();
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
