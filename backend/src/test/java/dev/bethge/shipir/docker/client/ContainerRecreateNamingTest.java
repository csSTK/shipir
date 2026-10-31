package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RenameContainerCmd;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.HostConfig;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ContainerCreateSpec;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The write side of the leftover reconciliation: the temporary name of the old container carries whether it was
 * running ({@code -r}), and is registered as in flight while the recreate renames things.
 */
class ContainerRecreateNamingTest {

    private static final String OLD_NAME = "web-shipir-old-[0-9a-f]{8}";

    private final DockerHostConnectionManager manager = mock(DockerHostConnectionManager.class);
    private final DockerClient client = mock(DockerClient.class, Answers.RETURNS_DEEP_STUBS);
    private final ContainerServiceImpl service = new ContainerServiceImpl(manager);
    /** Names given to {@code oldId} by rename, and whether each was registered in flight at that moment. */
    private final List<String> oldRenames = new ArrayList<>();
    private final List<Boolean> inFlightDuringRename = new ArrayList<>();

    private void stubRecreate(boolean running) {
        when(manager.readTimeout()).thenReturn(Duration.ofSeconds(2));
        when(manager.client(any(), any())).thenReturn(client);
        InspectContainerResponse old = mock(InspectContainerResponse.class, Answers.RETURNS_DEEP_STUBS);
        when(old.getName()).thenReturn("/web");
        when(old.getState().getRunning()).thenReturn(running);
        when(old.getConfig().getImage()).thenReturn("busybox:1.36");
        when(old.getHostConfig()).thenReturn(HostConfig.newHostConfig());
        when(old.getMounts()).thenReturn(List.of());
        when(client.inspectContainerCmd("old1").exec()).thenReturn(old);

        CreateContainerResponse created = mock(CreateContainerResponse.class);
        when(created.getId()).thenReturn("new1");
        CreateContainerCmd create = mock(CreateContainerCmd.class, invocation ->
                invocation.getMethod().getName().equals("exec") ? created
                        : CreateContainerCmd.class.isAssignableFrom(invocation.getMethod().getReturnType())
                        ? invocation.getMock() : null);
        when(client.createContainerCmd(any())).thenReturn(create);

        when(client.renameContainerCmd(any())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            RenameContainerCmd cmd = mock(RenameContainerCmd.class);
            when(cmd.withName(any())).thenAnswer(n -> {
                if (id.equals("old1")) {
                    String name = n.getArgument(0);
                    oldRenames.add(name);
                    inFlightDuringRename.add(service.recreatesInFlight.contains(name));
                }
                return cmd;
            });
            return cmd;
        });
    }

    private void recreate() {
        var spec = new ContainerCreateSpec("web", "busybox:1.36", List.of("sleep", "300"), Map.of(), List.of(), List.of(),
                List.of(), null, false, false, true);
        service.recreateContainer(UUID.randomUUID(), new DockerEndpoint.Local("unix:///x.sock"), "old1", spec);
    }

    @Test
    void aRunningOldContainerIsRenamedWithTheWasRunningSuffix() {
        stubRecreate(true);

        recreate();

        assertThat(oldRenames).singleElement().satisfies(n -> assertThat(n).matches(OLD_NAME + "-r"));
        assertThat(inFlightDuringRename).containsExactly(true);
        assertThat(service.recreatesInFlight).isEmpty();
    }

    @Test
    void aStoppedOldContainerIsRenamedWithoutTheSuffix() {
        stubRecreate(false);

        recreate();

        assertThat(oldRenames).singleElement().satisfies(n -> assertThat(n).matches(OLD_NAME));
        assertThat(inFlightDuringRename).containsExactly(true);
        assertThat(service.recreatesInFlight).isEmpty();
    }

    @Test
    void theInFlightMarkIsClearedAfterAFailedRecreateToo() {
        stubRecreate(true);
        when(client.startContainerCmd("new1").exec()).thenThrow(new InternalServerErrorException("boom"));

        assertThatThrownBy(this::recreate).isInstanceOf(DockerConnectionException.class);

        assertThat(oldRenames).hasSize(2); // the temporary name, then the rollback's rename back to "web"
        assertThat(oldRenames.get(0)).matches(OLD_NAME + "-r");
        assertThat(oldRenames.get(1)).isEqualTo("web");
        assertThat(inFlightDuringRename.get(0)).isTrue();
        assertThat(service.recreatesInFlight).isEmpty();
    }
}
