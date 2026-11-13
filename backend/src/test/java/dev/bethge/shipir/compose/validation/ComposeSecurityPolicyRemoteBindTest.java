package dev.bethge.shipir.compose.validation;

import dev.bethge.shipir.compose.DeploymentProperties;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.VolumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bind mounts on a remote (TCP) host (shipr-kqbc.61): the paths would refer to the remote filesystem, which Shipir cannot
 * check, so every bind mount is denied for non-ADMIN runs. LOCAL hosts keep the inside-the-deployment-directory rule.
 */
class ComposeSecurityPolicyRemoteBindTest {

    @TempDir
    Path tmp;

    private final UUID host = UUID.randomUUID();
    private final DockerEndpoint local = new DockerEndpoint.Local("unix:///var/run/docker.sock");
    private final DockerEndpoint tcp = new DockerEndpoint.Tcp("remote.example", 2376, null);
    private ComposeExecutionService execution;
    private ComposeSecurityPolicy policy;
    private Path dir;

    @BeforeEach
    void setUp() {
        ComposeFileRepository files = new ComposeFileRepository(new DeploymentProperties(tmp));
        files.write(host, "web", "services: {}\n", null);
        dir = files.deploymentDir(host, "web");
        execution = mock(ComposeExecutionService.class);
        policy = new ComposeSecurityPolicy(execution, files, mock(VolumeService.class));
    }

    private List<String> remote(String json) {
        return ComposeSecurityPolicy.violationsOf(JsonMapper.builder().build().readTree(json), dir, true);
    }

    private String service(String volumes) {
        return "{\"services\":{\"x\":{\"image\":\"alpine\",\"volumes\":" + volumes + "}}}";
    }

    @ParameterizedTest
    @ValueSource(strings = {"/srv/data", "/etc", "/var/run/docker.sock"})
    void deniesAbsoluteBindOnRemoteHost(String source) {
        assertThat(remote(service("[{\"type\":\"bind\",\"source\":\"" + source + "\",\"target\":\"/d\"}]")))
                .singleElement().asString().contains("x: ").contains(ComposeSecurityPolicy.REMOTE_BIND_MARKER);
    }

    @Test
    void deniesBindInsideTheDeploymentDirectoryOnRemoteHost() {
        assertThat(remote(service("[{\"type\":\"bind\",\"source\":\"" + dir + "/data\",\"target\":\"/d\"}]")))
                .singleElement().asString().contains(ComposeSecurityPolicy.REMOTE_BIND_MARKER);
    }

    @Test
    void deniesRelativeBindOnRemoteHost() {
        // compose resolves ./x to an absolute path in the config output; an unresolved ./x must be denied just the same
        assertThat(remote(service("[{\"type\":\"bind\",\"source\":\"./x\",\"target\":\"/d\"}]")))
                .singleElement().asString().contains(ComposeSecurityPolicy.REMOTE_BIND_MARKER);
    }

    @Test
    void deniesBindViaVolumeDriverOptsOnRemoteHost() {
        String json = "{\"volumes\":{\"v\":{\"driver_opts\":{\"type\":\"none\",\"o\":\"bind\",\"device\":\"" + dir + "/data\"}}}}";
        assertThat(remote(json)).singleElement().asString().contains(ComposeSecurityPolicy.REMOTE_BIND_MARKER);
    }

    @Test
    void allowsNamedVolumesAndTmpfsOnRemoteHost() {
        assertThat(remote(service("[{\"type\":\"volume\",\"source\":\"data\",\"target\":\"/d\"},"
                + "{\"type\":\"tmpfs\",\"target\":\"/t\"}]"))).isEmpty();
    }

    @Test
    void localHostKeepsTheInsideDirectoryRule() {
        var cfg = JsonMapper.builder().build().readTree(service("[{\"type\":\"bind\",\"source\":\"" + dir + "/data\",\"target\":\"/d\"}]"));
        assertThat(ComposeSecurityPolicy.violationsOf(cfg, dir)).isEmpty();
        var outside = JsonMapper.builder().build().readTree(service("[{\"type\":\"bind\",\"source\":\"/etc\",\"target\":\"/d\"}]"));
        assertThat(ComposeSecurityPolicy.violationsOf(outside, dir)).containsExactly("x: bind mount /etc");
    }

    private void config(String json) {
        when(execution.execute(any(), any(), eq(ComposeCommand.CONFIG_JSON), any(), any(), any(), any(), any()))
                .thenReturn(new ComposeProcessResult(0, false, List.of(json)));
    }

    @Test
    void violationsPicksTheRuleFromTheEndpointType() {
        config("{\"name\":\"web\",\"services\":{\"x\":{\"image\":\"alpine\",\"volumes\":[{\"type\":\"bind\",\"source\":\""
                + dir + "/data\",\"target\":\"/d\"}]}}}");
        assertThat(policy.violations(host, "web", local, null)).isEmpty();
        assertThat(policy.violations(host, "web", tcp, null)).singleElement().asString()
                .contains(ComposeSecurityPolicy.REMOTE_BIND_MARKER);
    }
}
