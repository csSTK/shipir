package dev.bethge.shipir.docker.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Inspect mapping from a realistic daemon JSON payload; no docker-java types in the result. */
class ContainerInspectTest {

    private static final DockerEndpoint ENDPOINT = new DockerEndpoint.Local("unix:///x.sock");
    private final UUID hostId = UUID.randomUUID();
    private DockerClient client;
    private ContainerServiceImpl service;

    private static final String JSON = """
            {"Id":"abc","Image":"sha256:img1","Name":"/shop-web-1","Created":"2026-01-02T03:04:05.123456789Z","Path":"nginx","Args":["-g","daemon off;"],
             "RestartCount":4,
             "State":{"Status":"running","Running":true,"StartedAt":"2026-01-02T03:04:06Z",
               "Health":{"Status":"unhealthy","FailingStreak":3,"Log":[
                 {"Start":"2026-01-02T03:05:00Z","End":"2026-01-02T03:05:01Z","ExitCode":1,"Output":"l1"},
                 {"Start":"2026-01-02T03:05:10Z","End":"2026-01-02T03:05:11Z","ExitCode":1,"Output":"l2"},
                 {"Start":"2026-01-02T03:05:20Z","End":"2026-01-02T03:05:21Z","ExitCode":1,"Output":"l3"},
                 {"Start":"2026-01-02T03:05:30Z","End":"2026-01-02T03:05:31Z","ExitCode":1,"Output":"l4"},
                 {"Start":"2026-01-02T03:05:40Z","End":"2026-01-02T03:05:41Z","ExitCode":1,"Output":"l5"},
                 {"Start":"2026-01-02T03:05:50Z","End":"2026-01-02T03:05:51Z","ExitCode":0,"Output":"l6"}]}},
             "Config":{"Image":"nginx:1.27","Cmd":["-g","daemon off;"],"Env":["A=1","SECRET=x=y","EMPTY"],
               "Labels":{"com.docker.compose.project":"shop","com.docker.compose.service":"web","z":"1"}},
             "HostConfig":{"Privileged":true,"NetworkMode":"host","RestartPolicy":{"Name":"on-failure","MaximumRetryCount":5}},
             "Mounts":[{"Type":"volume","Name":"data","Source":"/var/lib/docker/volumes/data/_data","Destination":"/data","RW":true},
                       {"Type":"bind","Source":"/srv/conf","Destination":"/etc/conf","RW":false}],
             "NetworkSettings":{"Ports":{"80/tcp":[{"HostIp":"0.0.0.0","HostPort":"8080"}],"443/tcp":null},
               "Networks":{"shop_default":{"IPAddress":"172.18.0.2","Gateway":"172.18.0.1","MacAddress":"02:42:ac:12:00:02","Aliases":["web","abc"]}}}}
            """;

    @BeforeEach
    void setUp() throws Exception {
        DockerHostConnectionManager manager = mock(DockerHostConnectionManager.class);
        client = mock(DockerClient.class, Answers.RETURNS_DEEP_STUBS);
        when(manager.readTimeout()).thenReturn(Duration.ofMillis(300));
        when(manager.client(any(), any())).thenReturn(client);
        service = new ContainerServiceImpl(manager);
        when(client.inspectContainerCmd("abc").exec()).thenReturn(readJson(JSON));
        when(client.inspectImageCmd("sha256:img1").exec()).thenReturn(new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue("{\"Config\":{\"Env\":[\"PATH=/usr/bin\",\"A=0\",\"NGINX_VERSION=1.27\"]}}",
                        com.github.dockerjava.api.command.InspectImageResponse.class));
    }

    @Test
    void exposesTheImageBakedEnvSeparatelyFromTheContainerEnv() {
        ContainerDetailsDto d = service.inspectWithImageEnv(hostId, ENDPOINT, "abc");

        assertThat(d.imageEnv()).containsExactly(new ContainerDetailsDto.EnvVar("PATH", "/usr/bin"),
                new ContainerDetailsDto.EnvVar("A", "0"), new ContainerDetailsDto.EnvVar("NGINX_VERSION", "1.27"));
    }

    @Test
    void imageEnvIsEmptyWhenTheImageCannotBeInspected() throws Exception {
        when(client.inspectImageCmd("sha256:img1").exec()).thenThrow(new NotFoundException("gone"));

        assertThat(service.inspectWithImageEnv(hostId, ENDPOINT, "abc").imageEnv()).isEmpty();
    }

    @Test
    void plainInspectDoesNotInspectTheImage() {
        clearInvocations(client); // drop the stubbing calls made in setUp
        ContainerDetailsDto d = service.inspect(hostId, ENDPOINT, "abc");

        assertThat(d.imageEnv()).isEmpty();
        assertThat(d.name()).isEqualTo("shop-web-1");
        verify(client, never()).inspectImageCmd(any());
    }

    @Test
    void inspectWithImageEnvInspectsTheImageOnce() {
        clearInvocations(client);
        service.inspectWithImageEnv(hostId, ENDPOINT, "abc");

        verify(client, times(1)).inspectImageCmd("sha256:img1");
    }

    @Test
    void withoutEnvValuesAlsoWithholdsTheImageEnvValues() {
        ContainerDetailsDto d = service.inspectWithImageEnv(hostId, ENDPOINT, "abc").withoutEnvValues();

        assertThat(d.imageEnv()).extracting(ContainerDetailsDto.EnvVar::value).containsOnlyNulls();
    }

    private static InspectContainerResponse readJson(String json) throws Exception {
        return new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(json, InspectContainerResponse.class);
    }

    @Test
    void mapsEnvMountsLabelsNetworksPortsRestartPolicyAndHealth() {
        ContainerDetailsDto d = service.inspect(hostId, ENDPOINT, "abc");

        assertThat(d.name()).isEqualTo("shop-web-1");
        assertThat(d.image()).isEqualTo("nginx:1.27");
        assertThat(d.state()).isEqualTo("running");
        assertThat(d.startedAt()).isEqualTo(Instant.parse("2026-01-02T03:04:06Z"));
        assertThat(d.restartCount()).isEqualTo(4);
        assertThat(d.command()).containsExactly("nginx", "-g", "daemon off;");
        assertThat(d.restartPolicy()).isEqualTo(new ContainerDetailsDto.RestartPolicy("on-failure", 5));
        assertThat(d.env()).containsExactly(new ContainerDetailsDto.EnvVar("A", "1"),
                new ContainerDetailsDto.EnvVar("SECRET", "x=y"), new ContainerDetailsDto.EnvVar("EMPTY", ""));
        assertThat(d.mounts()).containsExactly(
                new ContainerDetailsDto.Mount("volume", "data", "/var/lib/docker/volumes/data/_data", "/data", false),
                new ContainerDetailsDto.Mount("bind", null, "/srv/conf", "/etc/conf", true));
        assertThat(d.labels()).containsEntry("z", "1");
        assertThat(d.composeProject()).isEqualTo("shop");
        assertThat(d.composeService()).isEqualTo("web");
        assertThat(d.networks()).containsExactly(new ContainerDetailsDto.Network("shop_default", "172.18.0.2",
                "172.18.0.1", "02:42:ac:12:00:02", java.util.List.of("web", "abc")));
        assertThat(d.ports()).containsExactly(
                new ContainerDetailsDto.Port(80, "tcp", "0.0.0.0", 8080),
                new ContainerDetailsDto.Port(443, "tcp", null, null));
    }

    @Test
    void exposesConfiguredCmdSeparatelyFromEntrypointAndTheHostConfigFlags() {
        ContainerDetailsDto d = service.inspect(hostId, ENDPOINT, "abc");

        assertThat(d.cmd()).containsExactly("-g", "daemon off;");
        assertThat(d.privileged()).isTrue();
        assertThat(d.hostNetwork()).isTrue();
    }

    @Test
    void flagsAreFalseAndCmdEmptyWhenAbsent() throws Exception {
        InspectContainerResponse plain = readJson(JSON.replace(",\"Cmd\":[\"-g\",\"daemon off;\"]", "")
                .replace("\"Privileged\":true,\"NetworkMode\":\"host\",", "\"NetworkMode\":\"bridge\","));
        when(client.inspectContainerCmd("plain").exec()).thenReturn(plain);

        ContainerDetailsDto d = service.inspect(hostId, ENDPOINT, "plain");

        assertThat(d.cmd()).isEmpty();
        assertThat(d.privileged()).isFalse();
        assertThat(d.hostNetwork()).isFalse();
    }

    @Test
    void mapsMemoryLimitInBytesAndNanoCpusAsCpus() throws Exception {
        InspectContainerResponse limited = readJson(JSON.replace("\"HostConfig\":{\"Privileged\":true,",
                "\"HostConfig\":{\"Memory\":536870912,\"NanoCpus\":1500000000,\"Privileged\":true,"));
        when(client.inspectContainerCmd("limited").exec()).thenReturn(limited);

        ContainerDetailsDto d = service.inspect(hostId, ENDPOINT, "limited");

        assertThat(d.memoryBytes()).isEqualTo(536870912L);
        assertThat(d.cpus()).isEqualTo(1.5);
    }

    @Test
    void resourceLimitsAreNullWhenUnsetOrZero() throws Exception {
        InspectContainerResponse zero = readJson(JSON.replace("\"HostConfig\":{\"Privileged\":true,",
                "\"HostConfig\":{\"Memory\":0,\"NanoCpus\":0,\"Privileged\":true,"));
        when(client.inspectContainerCmd("zero").exec()).thenReturn(zero);

        ContainerDetailsDto unset = service.inspect(hostId, ENDPOINT, "abc");
        ContainerDetailsDto unlimited = service.inspect(hostId, ENDPOINT, "zero");

        assertThat(unset.memoryBytes()).isNull();
        assertThat(unset.cpus()).isNull();
        assertThat(unlimited.memoryBytes()).isNull();
        assertThat(unlimited.cpus()).isNull();
    }

    @Test
    void healthKeepsOnlyLastFiveLogs() {
        ContainerDetailsDto.Health h = service.inspect(hostId, ENDPOINT, "abc").health();

        assertThat(h.status()).isEqualTo("unhealthy");
        assertThat(h.failingStreak()).isEqualTo(3);
        assertThat(h.recentLogs()).extracting(ContainerDetailsDto.HealthLog::output).containsExactly("l2", "l3", "l4", "l5", "l6");
    }

    @Test
    void withoutEnvValuesKeepsKeysOnly() {
        ContainerDetailsDto d = service.inspect(hostId, ENDPOINT, "abc").withoutEnvValues();
        assertThat(d.env()).extracting(ContainerDetailsDto.EnvVar::key).containsExactly("A", "SECRET", "EMPTY");
        assertThat(d.env()).extracting(ContainerDetailsDto.EnvVar::value).containsOnlyNulls();
    }

    @Test
    void unknownContainerIsNotFound() {
        when(client.inspectContainerCmd("gone").exec()).thenThrow(new NotFoundException("x"));
        assertThatThrownBy(() -> service.inspect(hostId, ENDPOINT, "gone")).isInstanceOfSatisfying(
                DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }
}
