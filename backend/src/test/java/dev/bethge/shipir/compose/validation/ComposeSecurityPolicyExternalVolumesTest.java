package dev.bethge.shipir.compose.validation;

import dev.bethge.shipir.compose.DeploymentProperties;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionException;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.VolumeDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** External named volumes (shipr-kqbc.36): non-ADMIN may only use external volumes labelled with their own project. */
class ComposeSecurityPolicyExternalVolumesTest {

    @TempDir
    Path tmp;

    private final UUID host = UUID.randomUUID();
    private final DockerEndpoint endpoint = new DockerEndpoint.Local("unix:///var/run/docker.sock");
    private ComposeExecutionService execution;
    private VolumeService volumes;
    private ComposeSecurityPolicy policy;

    @BeforeEach
    void setUp() {
        ComposeFileRepository files = new ComposeFileRepository(new DeploymentProperties(tmp));
        files.write(host, "web", "services: {}\n", null);
        execution = mock(ComposeExecutionService.class);
        volumes = mock(VolumeService.class);
        policy = new ComposeSecurityPolicy(execution, files, volumes);
    }

    private void config(String volumesJson) {
        when(execution.execute(any(), any(), eq(ComposeCommand.CONFIG_JSON), any(), any(), any(), any(), any()))
                .thenReturn(new ComposeProcessResult(0, false,
                        List.of("{\"name\":\"web\",\"services\":{},\"volumes\":" + volumesJson + "}")));
    }

    private List<String> run() {
        return policy.violations(host, "web", endpoint, null);
    }

    @Test
    void allowsExternalVolumeOfTheSameDeployment() {
        config("{\"d\":{\"name\":\"web_data\",\"external\":true}}");
        when(volumes.list(host, endpoint)).thenReturn(List.of(new VolumeDto("web_data", "local", "/x", null, false, "web")));
        assertThat(run()).isEmpty();
    }

    @Test
    void deniesExternalVolumeOfAnotherDeployment() {
        config("{\"d\":{\"name\":\"other_data\",\"external\":true}}");
        when(volumes.list(host, endpoint)).thenReturn(List.of(new VolumeDto("other_data", "local", "/x", null, false, "other")));
        assertThat(run()).containsExactly("volume d: external volume other_data does not belong to this deployment");
    }

    @Test
    void deniesExternalVolumeWithoutProjectLabelAndMissingVolume() {
        config("{\"a\":{\"name\":\"plain\",\"external\":true},\"b\":{\"name\":\"ghost\",\"external\":true}}");
        when(volumes.list(host, endpoint)).thenReturn(List.of(new VolumeDto("plain", "local", "/x", null, false)));
        assertThat(run()).containsExactly("volume a: external volume plain does not belong to this deployment",
                "volume b: external volume ghost does not belong to this deployment");
    }

    @Test
    void failsClosedWhenTheHostCannotBeAsked() {
        config("{\"d\":{\"name\":\"web_data\",\"external\":true}}");
        when(volumes.list(host, endpoint)).thenThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        assertThatThrownBy(this::run).isInstanceOf(ComposeExecutionException.class);
    }

    @Test
    void deniesNonExternalVolumeWhoseExplicitNameIsAnotherDeploymentsVolume() {
        config("{\"x\":{\"name\":\"other_data\"}}");
        when(volumes.list(host, endpoint)).thenReturn(List.of(new VolumeDto("other_data", "local", "/x", null, false, "other")));
        assertThat(run()).containsExactly("volume x: volume other_data belongs to another deployment or is not managed by Compose");
    }

    @Test
    void deniesNonExternalVolumeWhoseExplicitNameIsAnUnlabelledVolume() {
        config("{\"x\":{\"name\":\"plain\"}}");
        when(volumes.list(host, endpoint)).thenReturn(List.of(new VolumeDto("plain", "local", "/x", null, false)));
        assertThat(run()).containsExactly("volume x: volume plain belongs to another deployment or is not managed by Compose");
    }

    @Test
    void allowsNonExternalVolumeThatDoesNotExistYetAndTheDeploymentsOwnVolumes() {
        config("{\"x\":{\"name\":\"fresh\"},\"data\":{\"name\":\"web_data\"}}");
        when(volumes.list(host, endpoint)).thenReturn(List.of(new VolumeDto("web_data", "local", "/x", null, true, "web")));
        assertThat(run()).isEmpty();
    }

    @Test
    void usesTheKeyAsNameForExternalVolumeWithoutName() {
        config("{\"shared\":{\"external\":true}}");
        when(volumes.list(host, endpoint)).thenReturn(List.of(new VolumeDto("shared", "local", "/x", null, false, "other")));
        assertThat(run()).containsExactly("volume shared: external volume shared does not belong to this deployment");
        when(volumes.list(host, endpoint)).thenReturn(List.of(new VolumeDto("shared", "local", "/x", null, false, "web")));
        assertThat(run()).isEmpty();
    }

    @Test
    void doesNotAskDockerWithoutTopLevelVolumes() {
        config("{}");
        assertThat(run()).isEmpty();
        verify(volumes, never()).list(any(), any());
    }
}
