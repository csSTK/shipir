package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.BadRequestException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ContainerResourcesSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** updateResources error mapping: refused limits are UPDATE_REJECTED, a genuine daemon failure is not. */
class ContainerUpdateResourcesErrorTest {

    private static final DockerEndpoint ENDPOINT = new DockerEndpoint.Local("unix:///x.sock");
    private static final ContainerResourcesSpec SPEC = new ContainerResourcesSpec(1024L, null, null);
    private final UUID hostId = UUID.randomUUID();
    private DockerClient client;
    private ContainerServiceImpl service;

    @BeforeEach
    void setUp() {
        DockerHostConnectionManager manager = mock(DockerHostConnectionManager.class);
        client = mock(DockerClient.class, Answers.RETURNS_DEEP_STUBS);
        when(manager.readTimeout()).thenReturn(Duration.ofMillis(300));
        when(manager.client(any(), any())).thenReturn(client);
        service = new ContainerServiceImpl(manager);
    }

    private DockerErrorCode codeOf(Throwable thrown) {
        var cmd = client.updateContainerCmd("c1");
        org.mockito.Mockito.doThrow(thrown).when(cmd).exec();
        return org.assertj.core.api.Assertions.catchThrowableOfType(DockerConnectionException.class,
                () -> service.updateResources(hostId, ENDPOINT, "c1", SPEC)).getCode();
    }

    @Test
    void badRequestIsUpdateRejected() {
        assertThat(codeOf(new BadRequestException("invalid"))).isEqualTo(DockerErrorCode.UPDATE_REJECTED);
    }

    @Test
    void daemon500AboutTheLimitsIsUpdateRejected() {
        assertThat(codeOf(new InternalServerErrorException("Minimum memory limit allowed is 6MB")))
                .isEqualTo(DockerErrorCode.UPDATE_REJECTED);
    }

    @Test
    void genuineDaemon500IsNotReportedAsRejectedLimits() {
        assertThat(codeOf(new InternalServerErrorException("write /var/lib/docker/containers/x/hostconfig.json: no space left on device")))
                .isNotEqualTo(DockerErrorCode.UPDATE_REJECTED);
    }

    @Test
    void daemon500ValidationMessagesAreUpdateRejected() {
        assertThat(codeOf(new InternalServerErrorException("Cannot set both NanoCPUs and CPUPeriod")))
                .isEqualTo(DockerErrorCode.UPDATE_REJECTED);
        assertThat(codeOf(new InternalServerErrorException("Memory limit should be smaller than already set memoryswap limit")))
                .isEqualTo(DockerErrorCode.UPDATE_REJECTED);
    }

    @Test
    void runtimeFailuresMentioningMemoryOrLimitAreNotRejectedLimits() {
        assertThat(codeOf(new InternalServerErrorException("OCI runtime update failed: cannot allocate memory")))
                .isNotEqualTo(DockerErrorCode.UPDATE_REJECTED);
        assertThat(codeOf(new InternalServerErrorException(
                "OCI runtime update failed: failed to write memory.limit_in_bytes: device or resource busy")))
                .isNotEqualTo(DockerErrorCode.UPDATE_REJECTED);
        assertThat(codeOf(new InternalServerErrorException("cgroup: cannot set cpu limit: permission denied")))
                .isNotEqualTo(DockerErrorCode.UPDATE_REJECTED);
    }

    @Test
    void genuineDaemon500KeepsTheCause() {
        when(client.updateContainerCmd("c1").exec()).thenThrow(new InternalServerErrorException("boom"));
        assertThatThrownBy(() -> service.updateResources(hostId, ENDPOINT, "c1", SPEC))
                .isInstanceOf(DockerConnectionException.class)
                .hasCauseInstanceOf(InternalServerErrorException.class);
    }
}
