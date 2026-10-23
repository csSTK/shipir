package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.DockerSystemInfoDto;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DockerSystemServiceNullVolumesTest {

    @Test
    void infoTreatsNullVolumeListAsZero() {
        DockerHostConnectionManager manager = mock(DockerHostConnectionManager.class);
        DockerClient client = mock(DockerClient.class, Answers.RETURNS_DEEP_STUBS);
        UUID id = UUID.randomUUID();
        DockerEndpoint endpoint = new DockerEndpoint.Local("unix:///x.sock");
        when(manager.readTimeout()).thenReturn(Duration.ofSeconds(5));
        when(manager.client(id, endpoint)).thenReturn(client);
        when(client.infoCmd().exec()).thenReturn(new Info());
        when(client.listContainersCmd().withShowAll(true).exec()).thenReturn(List.of());
        when(client.listVolumesCmd().exec().getVolumes()).thenReturn(null);

        DockerSystemInfoDto info = new DockerSystemServiceImpl(manager).info(id, endpoint);

        assertThat(info.volumes()).isZero();
    }
}
