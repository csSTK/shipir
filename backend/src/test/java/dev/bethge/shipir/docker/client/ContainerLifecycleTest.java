package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Container lifecycle operations with a stubbed docker-java client: delegation, error mapping, deadline. */
class ContainerLifecycleTest {

    private static final DockerEndpoint ENDPOINT = new DockerEndpoint.Local("unix:///x.sock");
    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerClient client;
    private ContainerServiceImpl service;

    @BeforeEach
    void setUp() {
        manager = mock(DockerHostConnectionManager.class);
        client = mock(DockerClient.class, Answers.RETURNS_DEEP_STUBS);
        when(manager.readTimeout()).thenReturn(Duration.ofMillis(300));
        when(manager.client(any(), any())).thenReturn(client);
        service = new ContainerServiceImpl(manager);
    }

    private static void assertCode(Throwable t, DockerErrorCode code) {
        assertThat(t).isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void operationsDelegateWithTimeouts() {
        service.startContainer(hostId, ENDPOINT, "c1");
        service.stopContainer(hostId, ENDPOINT, "c1", 7);
        service.restartContainer(hostId, ENDPOINT, "c1", 9);
        service.pauseContainer(hostId, ENDPOINT, "c1");
        service.unpauseContainer(hostId, ENDPOINT, "c1");
        service.killContainer(hostId, ENDPOINT, "c1");

        verify(client).startContainerCmd("c1");
        verify(client.stopContainerCmd("c1")).withTimeout(7);
        verify(client.restartContainerCmd("c1")).withtTimeout(9);
        verify(client).pauseContainerCmd("c1");
        verify(client).unpauseContainerCmd("c1");
        verify(client).killContainerCmd("c1");
    }

    @Test
    void unknownContainerIsNotFound() {
        when(client.startContainerCmd("gone").exec()).thenThrow(new NotFoundException("x"));
        when(client.killContainerCmd("gone").exec()).thenThrow(new NotFoundException("x"));

        assertThatThrownBy(() -> service.startContainer(hostId, ENDPOINT, "gone"))
                .satisfies(e -> assertCode(e, DockerErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.killContainer(hostId, ENDPOINT, "gone"))
                .satisfies(e -> assertCode(e, DockerErrorCode.NOT_FOUND));
    }

    @Test
    void notModifiedAndConflictMapToInvalidState() {
        when(client.startContainerCmd("c1").exec()).thenThrow(new NotModifiedException("already started"));
        when(client.stopContainerCmd("c1").withTimeout(10).exec()).thenThrow(new NotModifiedException("already stopped"));
        when(client.pauseContainerCmd("c1").exec()).thenThrow(new ConflictException("not running"));
        when(client.unpauseContainerCmd("c1").exec()).thenThrow(new ConflictException("not paused"));
        when(client.killContainerCmd("c1").exec()).thenThrow(new ConflictException("not running"));
        when(client.restartContainerCmd("c1").withtTimeout(10).exec()).thenThrow(new ConflictException("paused"));

        assertThatThrownBy(() -> service.startContainer(hostId, ENDPOINT, "c1")).satisfies(e -> assertCode(e, DockerErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> service.stopContainer(hostId, ENDPOINT, "c1", 10)).satisfies(e -> assertCode(e, DockerErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> service.pauseContainer(hostId, ENDPOINT, "c1")).satisfies(e -> assertCode(e, DockerErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> service.unpauseContainer(hostId, ENDPOINT, "c1")).satisfies(e -> assertCode(e, DockerErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> service.killContainer(hostId, ENDPOINT, "c1")).satisfies(e -> assertCode(e, DockerErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> service.restartContainer(hostId, ENDPOINT, "c1", 10)).satisfies(e -> assertCode(e, DockerErrorCode.INVALID_STATE));
    }

    @Test
    void unpauseOfRunningContainerAnswered500MapsToInvalidState() {
        when(client.unpauseContainerCmd("c1").exec())
                .thenThrow(new com.github.dockerjava.api.exception.InternalServerErrorException("Container c1 is not paused"));
        when(client.pauseContainerCmd("c2").exec())
                .thenThrow(new com.github.dockerjava.api.exception.InternalServerErrorException("boom"));

        assertThatThrownBy(() -> service.unpauseContainer(hostId, ENDPOINT, "c1")).satisfies(e -> assertCode(e, DockerErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> service.pauseContainer(hostId, ENDPOINT, "c2")).satisfies(e -> assertCode(e, DockerErrorCode.UNREACHABLE));
    }

    @Test
    void stopDeadlineIncludesGracePeriodButPlainCallsTimeOut() {
        when(client.stopContainerCmd("slow").withTimeout(1).exec()).thenAnswer(i -> {
            Thread.sleep(700); // longer than the 300 ms read timeout, shorter than 300 ms + 1 s
            return null;
        });
        when(client.pauseContainerCmd("slow").exec()).thenAnswer(i -> {
            Thread.sleep(700);
            return null;
        });

        assertThatCode(() -> service.stopContainer(hostId, ENDPOINT, "slow", 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.pauseContainer(hostId, ENDPOINT, "slow"))
                .satisfies(e -> assertCode(e, DockerErrorCode.TIMEOUT));
    }
}
