package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ContainerCreateSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cleanup when attaching the extra networks fails right after the new container was created (shipr-19u). */
class ContainerNetworkAttachRollbackTest {

    private final DockerEndpoint endpoint = new DockerEndpoint.Local("unix:///x.sock");
    private DockerHostConnectionManager manager;
    private DockerClient client;
    private InternalServerErrorException attachError;

    @BeforeEach
    void setUp() {
        manager = mock(DockerHostConnectionManager.class);
        client = mock(DockerClient.class, Answers.RETURNS_DEEP_STUBS);
        when(manager.readTimeout()).thenReturn(Duration.ofSeconds(2));
        when(manager.client(any(), any())).thenReturn(client);

        CreateContainerResponse created = mock(CreateContainerResponse.class);
        when(created.getId()).thenReturn("new1");
        CreateContainerCmd create = mock(CreateContainerCmd.class, invocation ->
                invocation.getMethod().getName().equals("exec") ? created
                        : CreateContainerCmd.class.isAssignableFrom(invocation.getMethod().getReturnType())
                        ? invocation.getMock() : null);
        when(client.createContainerCmd(any())).thenReturn(create);

        // the second network is connected after create and the daemon refuses it
        attachError = new InternalServerErrorException("network gone");
        when(client.connectToNetworkCmd().withNetworkId(anyString()).withContainerId(anyString()).exec())
                .thenThrow(attachError);
    }

    private ContainerCreateSpec spec(boolean start) {
        return new ContainerCreateSpec("web", "busybox:1.36", List.of("sleep", "300"), Map.of(), List.of(), List.of(),
                List.of(new ContainerCreateSpec.Network("front", List.of()), new ContainerCreateSpec.Network("back", List.of())),
                null, false, false, start);
    }

    @Test
    void createRemovesTheNewContainerAndSurfacesTheAttachError() {
        assertThatThrownBy(() -> new ContainerServiceImpl(manager).createContainer(UUID.randomUUID(), endpoint, spec(true)))
                .isInstanceOf(DockerConnectionException.class).hasCause(attachError);

        verify(client.removeContainerCmd("new1")).withForce(true);
        verify(client, never()).startContainerCmd(any());
    }

    @Test
    void createStillSurfacesTheAttachErrorWhenTheCleanupFails() {
        when(client.removeContainerCmd("new1").withForce(true).withRemoveVolumes(true).exec())
                .thenThrow(new NotFoundException("already gone"));

        assertThatThrownBy(() -> new ContainerServiceImpl(manager).createContainer(UUID.randomUUID(), endpoint, spec(false)))
                .isInstanceOf(DockerConnectionException.class).hasCause(attachError);
    }

    @Test
    void recreateRemovesTheNewContainerSurfacesTheAttachErrorAndLeavesTheOldOneUntouched() {
        InspectContainerResponse old = mock(InspectContainerResponse.class, Answers.RETURNS_DEEP_STUBS);
        when(old.getName()).thenReturn("/web");
        when(old.getState().getRunning()).thenReturn(true);
        when(old.getConfig().getImage()).thenReturn("busybox:1.36");
        when(old.getHostConfig()).thenReturn(HostConfig.newHostConfig());
        when(old.getMounts()).thenReturn(List.of());
        when(client.inspectContainerCmd("old1").exec()).thenReturn(old);

        assertThatThrownBy(() -> new ContainerServiceImpl(manager).recreateContainer(UUID.randomUUID(), endpoint, "old1", spec(true)))
                .isInstanceOf(DockerConnectionException.class).hasCause(attachError);

        verify(client.removeContainerCmd("new1")).withForce(true);
        verify(client, never()).stopContainerCmd(any());
        verify(client, never()).renameContainerCmd(any());
        verify(client, never()).startContainerCmd(any());
        verify(client, never()).removeContainerCmd("old1");
    }
}
