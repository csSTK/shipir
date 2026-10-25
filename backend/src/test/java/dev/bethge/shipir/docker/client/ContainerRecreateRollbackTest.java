package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.HostConfig;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ContainerCreateSpec;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Rollback paths of recreate that a real daemon cannot produce on demand. */
class ContainerRecreateRollbackTest {

    @Test
    void stopFailingAfterTheDaemonStoppedTheOldContainerStillRestartsIt() {
        DockerHostConnectionManager manager = mock(DockerHostConnectionManager.class);
        DockerClient client = mock(DockerClient.class, Answers.RETURNS_DEEP_STUBS);
        when(manager.readTimeout()).thenReturn(Duration.ofSeconds(2));
        when(manager.client(any(), any())).thenReturn(client);

        InspectContainerResponse old = mock(InspectContainerResponse.class, Answers.RETURNS_DEEP_STUBS);
        when(old.getName()).thenReturn("/web");
        when(old.getState().getRunning()).thenReturn(true);
        when(old.getConfig().getImage()).thenReturn("busybox:1.36");
        when(old.getHostConfig()).thenReturn(HostConfig.newHostConfig());
        when(client.inspectContainerCmd("old1").exec()).thenReturn(old);

        CreateContainerResponse created = mock(CreateContainerResponse.class);
        when(created.getId()).thenReturn("new1");
        CreateContainerCmd create = mock(CreateContainerCmd.class, invocation ->
                invocation.getMethod().getName().equals("exec") ? created
                        : CreateContainerCmd.class.isAssignableFrom(invocation.getMethod().getReturnType())
                        ? invocation.getMock() : null);
        when(client.createContainerCmd(any())).thenReturn(create);
        when(client.stopContainerCmd("old1").withTimeout(any()).exec()).thenThrow(new InternalServerErrorException("boom"));

        var spec = new ContainerCreateSpec("web", "busybox:1.36", List.of("sleep", "300"), Map.of(), List.of(), List.of(),
                null, null, false, false, true);

        assertThatThrownBy(() -> new ContainerServiceImpl(manager).recreateContainer(UUID.randomUUID(),
                new DockerEndpoint.Local("unix:///x.sock"), "old1", spec))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo(DockerErrorCode.RECREATE_FAILED));
        verify(client).startContainerCmd("old1");
        verify(client).removeContainerCmd("new1");
    }
}
