package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Remove operations with a stubbed docker-java client: error mapping and built-in network refusal. */
class RemoveOperationsTest {

    private static final DockerEndpoint ENDPOINT = new DockerEndpoint.Local("unix:///x.sock");
    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerClient client;

    @BeforeEach
    void setUp() {
        manager = mock(DockerHostConnectionManager.class);
        client = mock(DockerClient.class, Answers.RETURNS_DEEP_STUBS);
        when(manager.readTimeout()).thenReturn(Duration.ofMillis(500));
        when(manager.client(any(), any())).thenReturn(client);
    }

    private static void assertCode(Throwable t, DockerErrorCode code) {
        assertThat(t).isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void containerRemovalPassesForceAndKeepsVolumes() {
        new ContainerServiceImpl(manager).removeContainer(hostId, ENDPOINT, "c1", true);

        verify(client.removeContainerCmd("c1").withForce(true)).withRemoveVolumes(false);
    }

    @Test
    void containerNotFoundAndConflictAreMapped() {
        var service = new ContainerServiceImpl(manager);
        when(client.removeContainerCmd("gone").withForce(false).withRemoveVolumes(false).exec())
                .thenThrow(new NotFoundException("no such container"));
        when(client.removeContainerCmd("busy").withForce(false).withRemoveVolumes(false).exec())
                .thenThrow(new ConflictException("running"));

        assertThatThrownBy(() -> service.removeContainer(hostId, ENDPOINT, "gone", false))
                .satisfies(e -> assertCode(e, DockerErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.removeContainer(hostId, ENDPOINT, "busy", false))
                .satisfies(e -> assertCode(e, DockerErrorCode.IN_USE));
    }

    @Test
    void imageNotFoundAndConflictAreMappedAndNeverForced() {
        var service = new ImageServiceImpl(manager);
        when(client.removeImageCmd("gone").withForce(false).exec()).thenThrow(new NotFoundException("x"));
        when(client.removeImageCmd("used").withForce(false).exec()).thenThrow(new ConflictException("x"));

        assertThatThrownBy(() -> service.removeImage(hostId, ENDPOINT, "gone"))
                .satisfies(e -> assertCode(e, DockerErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.removeImage(hostId, ENDPOINT, "used"))
                .satisfies(e -> assertCode(e, DockerErrorCode.IN_USE));
        verify(client.removeImageCmd("used"), never()).withForce(true);
    }

    @Test
    void volumeNotFoundAndConflictAreMapped() {
        var service = new VolumeServiceImpl(manager);
        when(client.removeVolumeCmd("gone").exec()).thenThrow(new NotFoundException("x"));
        when(client.removeVolumeCmd("used").exec()).thenThrow(new ConflictException("x"));

        assertThatThrownBy(() -> service.removeVolume(hostId, ENDPOINT, "gone"))
                .satisfies(e -> assertCode(e, DockerErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.removeVolume(hostId, ENDPOINT, "used"))
                .satisfies(e -> assertCode(e, DockerErrorCode.IN_USE));
    }

    @Test
    void networkNotFoundOnInspectAndConflictOnRemoveAreMapped() {
        var service = new NetworkServiceImpl(manager);
        when(client.inspectNetworkCmd().withNetworkId("gone").exec()).thenThrow(new NotFoundException("x"));
        when(client.inspectNetworkCmd().withNetworkId("used").exec().getName()).thenReturn("app_net");
        when(client.removeNetworkCmd("used").exec()).thenThrow(new ConflictException("x"));

        assertThatThrownBy(() -> service.removeNetwork(hostId, ENDPOINT, "gone"))
                .satisfies(e -> assertCode(e, DockerErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.removeNetwork(hostId, ENDPOINT, "used"))
                .satisfies(e -> assertCode(e, DockerErrorCode.IN_USE));
    }

    @Test
    void networkWithAttachedContainersAnswered403MapsToInUse() {
        when(client.inspectNetworkCmd().withNetworkId("n1").exec().getName()).thenReturn("app_net");
        when(client.removeNetworkCmd("n1").exec()).thenThrow(new com.github.dockerjava.api.exception.DockerException("active endpoints", 403));

        assertThatThrownBy(() -> new NetworkServiceImpl(manager).removeNetwork(hostId, ENDPOINT, "n1"))
                .satisfies(e -> assertCode(e, DockerErrorCode.IN_USE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bridge", "host", "none"})
    void builtinNetworksAreRefusedWithoutCallingRemove(String name) {
        when(client.inspectNetworkCmd().withNetworkId("id-" + name).exec().getName()).thenReturn(name);

        assertThatThrownBy(() -> new NetworkServiceImpl(manager).removeNetwork(hostId, ENDPOINT, "id-" + name))
                .satisfies(e -> assertCode(e, DockerErrorCode.BUILTIN_NETWORK));
        verify(client, never()).removeNetworkCmd(any());
    }

    @Test
    void userNetworkIsRemoved() {
        when(client.inspectNetworkCmd().withNetworkId("n1").exec().getName()).thenReturn("app_net");

        assertThatCode(() -> new NetworkServiceImpl(manager).removeNetwork(hostId, ENDPOINT, "n1"))
                .doesNotThrowAnyException();
        verify(client).removeNetworkCmd("n1");
    }

    @Test
    void hangingRemoveMapsToTimeout() {
        CountDownLatch never = new CountDownLatch(1);
        when(client.removeVolumeCmd("slow").exec()).thenAnswer(i -> {
            never.await();
            return null;
        });

        assertThatThrownBy(() -> new VolumeServiceImpl(manager).removeVolume(hostId, ENDPOINT, "slow"))
                .satisfies(e -> assertCode(e, DockerErrorCode.TIMEOUT));
        never.countDown();
    }
}
