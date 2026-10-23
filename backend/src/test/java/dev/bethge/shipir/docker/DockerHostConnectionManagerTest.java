package dev.bethge.shipir.docker;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DockerHostConnectionManagerTest {

    private final List<DockerClient> created = new ArrayList<>();
    private final DockerEndpoint endpoint = new DockerEndpoint.Local("unix:///var/run/docker.sock");
    private DockerHostConnectionManager manager;

    @BeforeEach
    void setUp() {
        DockerClientFactory fake = e -> {
            DockerClient client = mock(DockerClient.class);
            created.add(client);
            return client;
        };
        manager = new DockerHostConnectionManager(fake,
                new DockerProperties("unix:///custom.sock", Duration.ofSeconds(3), Duration.ofSeconds(10)));
    }

    @Test
    void returnsSameClientForSameHostId() {
        UUID id = UUID.randomUUID();

        assertThat(manager.client(id, endpoint)).isSameAs(manager.client(id, endpoint));
        assertThat(created).hasSize(1);
    }

    @Test
    void returnsDifferentClientsForDifferentHosts() {
        assertThat(manager.client(UUID.randomUUID(), endpoint))
                .isNotSameAs(manager.client(UUID.randomUUID(), endpoint));
    }

    @Test
    void evictClosesClientAndNextCallCreatesNewOne() throws IOException {
        UUID id = UUID.randomUUID();
        DockerClient first = manager.client(id, endpoint);

        manager.evict(id);

        verify(first).close();
        assertThat(manager.client(id, endpoint)).isNotSameAs(first);
    }

    @Test
    void evictOfUnknownHostDoesNothing() throws IOException {
        UUID id = UUID.randomUUID();
        DockerClient other = manager.client(id, endpoint);

        manager.evict(UUID.randomUUID());

        verify(other, never()).close();
    }

    @Test
    void createClientIsNotCached() {
        assertThat(manager.createClient(endpoint)).isNotSameAs(manager.createClient(endpoint));
    }

    @Test
    void localEndpointUsesConfiguredSocket() {
        assertThat(manager.localEndpoint()).isEqualTo(new DockerEndpoint.Local("unix:///custom.sock"));
    }
}
