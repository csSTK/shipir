package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerMount;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ContainerNetworkSettings;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Network;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.docker.dto.NetworkDto;
import dev.bethge.shipir.docker.dto.VolumeDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Image, network and volume listing: fake client data for mapping/inUse, real sockets for failures. */
class ResourceServicesTest {

    private static final DockerEndpoint ENDPOINT = new DockerEndpoint.Local("unix:///x.sock");

    @TempDir
    Path dir;

    private final List<SocketChannel> accepted = new ArrayList<>();
    private ServerSocketChannel server;

    @AfterEach
    void tearDown() throws IOException {
        for (SocketChannel c : accepted) c.close();
        if (server != null) server.close();
    }

    private static DockerHostConnectionManager managerWith(DockerClient client) {
        DockerHostConnectionManager manager = mock(DockerHostConnectionManager.class);
        when(manager.readTimeout()).thenReturn(Duration.ofSeconds(5));
        when(manager.client(any(), any())).thenReturn(client);
        return manager;
    }

    private static DockerClient client(List<Container> containers, Consumer<DockerClient> setup) {
        DockerClient client = mock(DockerClient.class, Answers.RETURNS_DEEP_STUBS);
        when(client.listContainersCmd().withShowAll(true).exec()).thenReturn(containers);
        setup.accept(client);
        return client;
    }

    private static Container container(String imageId, List<String> networks, List<String> volumes) {
        Container c = mock(Container.class);
        when(c.getImageId()).thenReturn(imageId);
        ContainerNetworkSettings settings = mock(ContainerNetworkSettings.class);
        Map<String, ContainerNetwork> map = new java.util.LinkedHashMap<>();
        networks.forEach(n -> map.put(n, mock(ContainerNetwork.class)));
        when(settings.getNetworks()).thenReturn(map);
        when(c.getNetworkSettings()).thenReturn(settings);
        List<ContainerMount> mounts = volumes.stream().map(v -> {
            ContainerMount m = mock(ContainerMount.class);
            when(m.getName()).thenReturn(v);
            return m;
        }).toList();
        when(c.getMounts()).thenReturn(mounts);
        return c;
    }

    private static Image image(String id, String[] tags) {
        Image i = mock(Image.class);
        when(i.getId()).thenReturn(id);
        when(i.getRepoTags()).thenReturn(tags);
        when(i.getSize()).thenReturn(1234L);
        when(i.getCreated()).thenReturn(1_700_000_000L);
        return i;
    }

    // --- images ---

    @Test
    void imageInUseWhenAnyContainerRunsIt() {
        Image used = image("sha256:a", new String[]{"nginx:1"});
        Image unused = image("sha256:b", new String[]{"redis:7", "redis:latest"});
        DockerClient client = client(List.of(container("sha256:a", List.of(), List.of())),
                c -> when(c.listImagesCmd().exec()).thenReturn(List.of(used, unused)));

        List<ImageDto> images = new ImageServiceImpl(managerWith(client)).list(UUID.randomUUID(), ENDPOINT);

        assertThat(images).containsExactly(
                new ImageDto("sha256:a", List.of("nginx:1"), 1234L, Instant.ofEpochSecond(1_700_000_000L), true),
                new ImageDto("sha256:b", List.of("redis:7", "redis:latest"), 1234L,
                        Instant.ofEpochSecond(1_700_000_000L), false));
    }

    @Test
    void danglingImagesHaveNoTagsWhetherNullEmptyOrNonePlaceholder() {
        Image nullTags = image("sha256:1", null);
        Image emptyTags = image("sha256:2", new String[0]);
        Image none = image("sha256:3", new String[]{"<none>:<none>"});
        DockerClient client = client(List.of(),
                c -> when(c.listImagesCmd().exec()).thenReturn(List.of(nullTags, emptyTags, none)));

        List<ImageDto> images = new ImageServiceImpl(managerWith(client)).list(UUID.randomUUID(), ENDPOINT);

        assertThat(images).hasSize(3).allSatisfy(i -> {
            assertThat(i.repoTags()).isEmpty();
            assertThat(i.inUse()).isFalse();
        });
    }

    // --- networks ---

    private static Network network(String id, String name, String subnet, Boolean internal) {
        Network n = mock(Network.class, Answers.RETURNS_DEEP_STUBS);
        when(n.getId()).thenReturn(id);
        when(n.getName()).thenReturn(name);
        when(n.getDriver()).thenReturn("bridge");
        when(n.getScope()).thenReturn("local");
        when(n.getInternal()).thenReturn(internal);
        if (subnet == null) {
            when(n.getIpam()).thenReturn(null);
        } else {
            Network.Ipam.Config cfg = mock(Network.Ipam.Config.class);
            when(cfg.getSubnet()).thenReturn(subnet);
            when(n.getIpam().getConfig()).thenReturn(List.of(cfg));
        }
        return n;
    }

    @Test
    void networkContainerCountComesFromContainerList() {
        Network shop = network("n1", "shop_default", "172.20.0.0/16", false);
        Network empty = network("n2", "unused", null, null);
        Network internal = network("n3", "backend", "10.0.0.0/24", true);
        DockerClient client = client(List.of(
                        container("i", List.of("shop_default"), List.of()),
                        container("i", List.of("shop_default", "backend"), List.of()),
                        container("i", List.of(), List.of())),
                c -> when(c.listNetworksCmd().exec()).thenReturn(List.of(shop, empty, internal)));

        List<NetworkDto> networks = new NetworkServiceImpl(managerWith(client)).list(UUID.randomUUID(), ENDPOINT);

        assertThat(networks).containsExactly(
                new NetworkDto("n1", "shop_default", "bridge", "local", "172.20.0.0/16", false, 2),
                new NetworkDto("n2", "unused", "bridge", "local", null, false, 0),
                new NetworkDto("n3", "backend", "bridge", "local", "10.0.0.0/24", true, 1));
    }

    // --- volumes ---

    private static InspectVolumeResponse volume(String name, Object createdAt) {
        InspectVolumeResponse v = mock(InspectVolumeResponse.class);
        when(v.getName()).thenReturn(name);
        when(v.getDriver()).thenReturn("local");
        when(v.getMountpoint()).thenReturn("/var/lib/docker/volumes/" + name + "/_data");
        Map<String, Object> raw = new java.util.HashMap<>();
        if (createdAt != null) raw.put("CreatedAt", createdAt);
        when(v.getRawValues()).thenReturn(raw);
        return v;
    }

    @Test
    void volumeInUseWhenAnyContainerMountsItByName() {
        ListVolumesResponse response = mock(ListVolumesResponse.class);
        List<InspectVolumeResponse> vols = List.of(
                volume("data", "2024-03-01T10:15:30Z"), volume("orphan", "2024-03-02T10:15:30+01:00"));
        when(response.getVolumes()).thenReturn(vols);
        DockerClient client = client(List.of(container("i", List.of(), List.of("data"))),
                c -> when(c.listVolumesCmd().exec()).thenReturn(response));

        List<VolumeDto> volumes = new VolumeServiceImpl(managerWith(client)).list(UUID.randomUUID(), ENDPOINT);

        assertThat(volumes).containsExactly(
                new VolumeDto("data", "local", "/var/lib/docker/volumes/data/_data",
                        Instant.parse("2024-03-01T10:15:30Z"), true),
                new VolumeDto("orphan", "local", "/var/lib/docker/volumes/orphan/_data",
                        Instant.parse("2024-03-02T09:15:30Z"), false));
    }

    @Test
    void volumeWithMissingOrUnparsableCreatedAtHasNullCreated() {
        ListVolumesResponse response = mock(ListVolumesResponse.class);
        List<InspectVolumeResponse> vols = List.of(volume("a", null), volume("b", "garbage"));
        when(response.getVolumes()).thenReturn(vols);
        DockerClient client = client(List.of(),
                c -> when(c.listVolumesCmd().exec()).thenReturn(response));

        assertThat(new VolumeServiceImpl(managerWith(client)).list(UUID.randomUUID(), ENDPOINT))
                .extracting(VolumeDto::created).containsExactly(null, null);
    }

    @Test
    void nullVolumeListYieldsEmptyList() {
        ListVolumesResponse response = mock(ListVolumesResponse.class);
        when(response.getVolumes()).thenReturn(null);
        DockerClient client = client(List.of(), c -> when(c.listVolumesCmd().exec()).thenReturn(response));

        assertThat(new VolumeServiceImpl(managerWith(client)).list(UUID.randomUUID(), ENDPOINT)).isEmpty();
    }

    // --- failures ---

    private DockerHostConnectionManager realManager(Duration read) {
        return new DockerHostConnectionManager(new DockerProperties("unix:///unused.sock", Duration.ofSeconds(1), read));
    }

    @Test
    void missingSocketThrowsUnreachable() {
        DockerHostConnectionManager manager = realManager(Duration.ofSeconds(1));
        var endpoint = new DockerEndpoint.Local("unix://" + dir.resolve("missing.sock"));

        for (var call : List.<java.util.function.Supplier<?>>of(
                () -> new ImageServiceImpl(manager).list(UUID.randomUUID(), endpoint),
                () -> new NetworkServiceImpl(manager).list(UUID.randomUUID(), endpoint),
                () -> new VolumeServiceImpl(manager).list(UUID.randomUUID(), endpoint))) {
            assertThatThrownBy(call::get).isInstanceOfSatisfying(DockerConnectionException.class,
                    e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.UNREACHABLE));
        }
    }

    @Test
    void hangingDaemonThrowsTimeout() throws IOException {
        Path socket = Files.createTempFile("shipir", ".sock");
        Files.delete(socket);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        Thread acceptor = Thread.ofVirtual().start(() -> {
            try {
                while (true) accepted.add(server.accept());
            } catch (IOException ignored) {
                // server closed
            }
        });
        DockerHostConnectionManager manager = realManager(Duration.ofMillis(500));
        var endpoint = new DockerEndpoint.Local("unix://" + socket);

        long start = System.nanoTime();
        for (var call : List.<java.util.function.Supplier<?>>of(
                () -> new ImageServiceImpl(manager).list(UUID.randomUUID(), endpoint),
                () -> new NetworkServiceImpl(manager).list(UUID.randomUUID(), endpoint),
                () -> new VolumeServiceImpl(manager).list(UUID.randomUUID(), endpoint))) {
            assertThatThrownBy(call::get).isInstanceOfSatisfying(DockerConnectionException.class,
                    e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.TIMEOUT));
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        acceptor.interrupt();
        Files.deleteIfExists(socket);
    }
}
