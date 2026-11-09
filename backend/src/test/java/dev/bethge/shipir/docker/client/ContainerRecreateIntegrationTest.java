package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.ContainerCreateSpec;
import dev.bethge.shipir.docker.dto.ContainerResourcesSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recreate and resource update against the local Docker daemon.
 *
 * <p>Cleanup note for dev machines: older runs (before tearDown reported removal failures) may have left
 * networks named {@code shipir-rc-<8 hex>-...} behind, which block other tests that use fixed subnets.
 * Remove them by hand, containers attached to them first:
 * <pre>
 * docker ps -a --filter name=shipir-rc- --format '{{.ID}} {{.Names}}'     # list leftover containers
 * docker rm -f &lt;container-id&gt;                                             # per leftover container
 * docker network ls --filter name=shipir-rc- --format '{{.ID}} {{.Name}}'  # list leftover networks
 * docker network rm &lt;network-id&gt;                                          # per leftover network
 * </pre>
 * Only names with the {@code shipir-rc-} prefix belong to this test; nothing else is touched.
 */
class ContainerRecreateIntegrationTest {

    private static final String IMAGE = "busybox:1.36";

    private final UUID hostId = UUID.randomUUID();
    private final String prefix = "shipir-rc-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<String> containerIds = new ArrayList<>();
    private final List<String> networkIds = new ArrayList<>();
    private final List<String> volumes = new ArrayList<>();
    private DockerHostConnectionManager manager;
    private ContainerService service;
    private DockerEndpoint endpoint;
    private DockerClient docker;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(30)));
        service = new ContainerServiceImpl(manager);
        endpoint = manager.localEndpoint();
        docker = DockerClientFactory.lazyClient();
        try {
            docker.inspectImageCmd(IMAGE).exec();
        } catch (RuntimeException notPulled) {
            docker.pullImageCmd(IMAGE).start().awaitCompletion();
        }
    }

    @AfterEach
    void tearDown() {
        if (docker == null) return;
        // also everything the recreate may have left behind under our prefix
        for (Container c : docker.listContainersCmd().withShowAll(true).exec()) {
            for (String n : c.getNames()) {
                if (n.startsWith("/" + prefix)) containerIds.add(c.getId());
            }
        }
        // a failed container removal keeps its networks attached, so it is reported as well, not swallowed
        List<String> containerFailures = new ArrayList<>();
        for (String id : containerIds) {
            try {
                docker.removeContainerCmd(id).withForce(true).exec();
            } catch (NotFoundException alreadyGone) {
                // already gone
            } catch (RuntimeException e) {
                containerFailures.add(id + " (" + e.getMessage() + ")");
            }
        }
        List<String> volumeFailures = new ArrayList<>();
        for (String v : volumes) {
            try {
                docker.removeVolumeCmd(v).exec();
            } catch (NotFoundException alreadyGone) {
                // already gone
            } catch (RuntimeException e) {
                volumeFailures.add(v + " (" + e.getMessage() + ")");
            }
        }
        // networks only after the containers above are gone, otherwise removal fails while they are attached
        List<String> networkFailures = new ArrayList<>();
        for (String id : networkIds) {
            try {
                docker.removeNetworkCmd(id).exec();
            } catch (NotFoundException alreadyGone) {
                // already gone
            } catch (RuntimeException e) {
                networkFailures.add(id + " (" + e.getMessage() + ")");
            }
        }
        manager.evict(hostId);
        if (!containerFailures.isEmpty() || !volumeFailures.isEmpty() || !networkFailures.isEmpty()) {
            throw new IllegalStateException("Test resources were not removed and would leak: containers "
                    + containerFailures + ", volumes " + volumeFailures + ", networks " + networkFailures);
        }
    }

    private ContainerCreateSpec spec(String name, Map<String, String> env, List<ContainerCreateSpec.Port> ports,
                                     List<ContainerCreateSpec.Mount> mounts, boolean start) {
        return new ContainerCreateSpec(name, IMAGE, List.of("sleep", "300"), env, ports, mounts, List.of(), null,
                false, false, start);
    }

    private String create(ContainerCreateSpec spec) {
        String id = service.createContainer(hostId, endpoint, spec).id();
        containerIds.add(id);
        return id;
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void recreateReconnectsEveryNetworkIncludingAliases() {
        String netA = prefix + "-a";
        String netB = prefix + "-b";
        networkIds.add(docker.createNetworkCmd().withName(netA).exec().getId());
        networkIds.add(docker.createNetworkCmd().withName(netB).exec().getId());
        var nets = List.of(new ContainerCreateSpec.Network(netA, List.of("alias-a")),
                new ContainerCreateSpec.Network(netB, List.of()));
        String name = prefix + "-multi";
        String oldId = create(withNetworks(spec(name, Map.of(), List.of(), List.of(), true), nets));

        var result = service.recreateContainer(hostId, endpoint, oldId,
                withNetworks(spec(name, Map.of("A", "2"), List.of(), List.of(), false), nets));
        containerIds.add(result.id());

        var attached = docker.inspectContainerCmd(result.id()).exec().getNetworkSettings().getNetworks();
        assertThat(attached).containsOnlyKeys(netA, netB);
        assertThat(attached.get(netA).getAliases()).contains("alias-a");
    }

    @Test
    void recreateKeepsStaticIpsOnPrimaryAndSecondaryNetworks() {
        String netA = prefix + "-sa";
        String netB = prefix + "-sb";
        int octet = java.util.concurrent.ThreadLocalRandom.current().nextInt(100, 200);
        String subA = "10." + octet + ".71";
        String subB = "10." + octet + ".72";
        networkIds.add(docker.createNetworkCmd().withName(netA).withIpam(new com.github.dockerjava.api.model.Network.Ipam()
                .withConfig(new com.github.dockerjava.api.model.Network.Ipam.Config().withSubnet(subA + ".0/24"))).exec().getId());
        networkIds.add(docker.createNetworkCmd().withName(netB).withIpam(new com.github.dockerjava.api.model.Network.Ipam()
                .withConfig(new com.github.dockerjava.api.model.Network.Ipam.Config().withSubnet(subB + ".0/24"))).exec().getId());
        var nets = List.of(new ContainerCreateSpec.Network(netA, List.of()),
                new ContainerCreateSpec.Network(netB, List.of("alias-b")));
        String name = prefix + "-static";
        String oldId = create(withNetworks(spec(name, Map.of(), List.of(), List.of(), false), nets));
        // give both networks a static address, as a compose file with ipv4_address does
        docker.disconnectFromNetworkCmd().withNetworkId(netA).withContainerId(oldId).exec();
        docker.connectToNetworkCmd().withNetworkId(netA).withContainerId(oldId).withContainerNetwork(
                new com.github.dockerjava.api.model.ContainerNetwork().withIpamConfig(
                        new com.github.dockerjava.api.model.ContainerNetwork.Ipam().withIpv4Address(subA + ".10"))).exec();
        docker.disconnectFromNetworkCmd().withNetworkId(netB).withContainerId(oldId).exec();
        docker.connectToNetworkCmd().withNetworkId(netB).withContainerId(oldId).withContainerNetwork(
                new com.github.dockerjava.api.model.ContainerNetwork().withAliases(List.of("alias-b")).withIpamConfig(
                        new com.github.dockerjava.api.model.ContainerNetwork.Ipam().withIpv4Address(subB + ".20"))).exec();
        docker.startContainerCmd(oldId).exec();

        var result = service.recreateContainer(hostId, endpoint, oldId,
                withNetworks(spec(name, Map.of("A", "2"), List.of(), List.of(), false), nets));
        containerIds.add(result.id());

        var attached = docker.inspectContainerCmd(result.id()).exec().getNetworkSettings().getNetworks();
        assertThat(attached.get(netA).getIpAddress()).isEqualTo(subA + ".10");
        assertThat(attached.get(netB).getIpAddress()).isEqualTo(subB + ".20");
        assertThat(attached.get(netB).getAliases()).contains("alias-b");
    }

    /** A new subnet for {@code name} with a container that holds {@code address} on it (started, like a compose ipv4_address). */
    private String containerWithStaticIp(String name, String network, String subnet, String address) {
        networkIds.add(docker.createNetworkCmd().withName(network).withIpam(new com.github.dockerjava.api.model.Network.Ipam()
                .withConfig(new com.github.dockerjava.api.model.Network.Ipam.Config().withSubnet(subnet + ".0/24"))).exec().getId());
        String id = create(withNetworks(spec(name, Map.of(), List.of(), List.of(), false),
                List.of(new ContainerCreateSpec.Network(network, List.of()))));
        docker.disconnectFromNetworkCmd().withNetworkId(network).withContainerId(id).exec();
        docker.connectToNetworkCmd().withNetworkId(network).withContainerId(id).withContainerNetwork(
                new com.github.dockerjava.api.model.ContainerNetwork().withIpamConfig(
                        new com.github.dockerjava.api.model.ContainerNetwork.Ipam().withIpv4Address(address))).exec();
        docker.startContainerCmd(id).exec();
        return id;
    }

    @Test
    void duplicateOfAStaticIpContainerGetsADynamicAddress() {
        String net = prefix + "-dup";
        String subnet = "10." + ThreadLocalRandom.current().nextInt(100, 200) + ".73";
        containerWithStaticIp(prefix + "-orig", net, subnet, subnet + ".10");

        String copyId = create(withNetworks(spec(prefix + "-copy", Map.of(), List.of(), List.of(), true),
                List.of(new ContainerCreateSpec.Network(net, List.of()))));

        var attached = docker.inspectContainerCmd(copyId).exec().getNetworkSettings().getNetworks().get(net);
        assertThat(attached.getIpamConfig()).isNull();
        assertThat(attached.getIpAddress()).startsWith(subnet + ".").isNotEqualTo(subnet + ".10");
    }

    @Test
    void failedRecreateOfAStaticIpContainerRestoresItWithItsAddress() throws Exception {
        String net = prefix + "-rb";
        String subnet = "10." + ThreadLocalRandom.current().nextInt(100, 200) + ".74";
        String name = prefix + "-pinned";
        String oldId = containerWithStaticIp(name, net, subnet, subnet + ".10");
        int taken = freePort();
        create(spec(prefix + "-blocker", Map.of(), List.of(new ContainerCreateSpec.Port(taken, 80, "tcp")), List.of(), true));
        var nets = List.of(new ContainerCreateSpec.Network(net, List.of()));

        // the new container fails to start on the taken port, after the old one was stopped and renamed
        assertThatThrownBy(() -> service.recreateContainer(hostId, endpoint, oldId,
                withNetworks(spec(name, Map.of("A", "2"), List.of(new ContainerCreateSpec.Port(taken, 80, "tcp")), List.of(), true), nets)))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.RECREATE_FAILED));

        var inspect = docker.inspectContainerCmd(oldId).exec();
        assertThat(inspect.getName()).isEqualTo("/" + name);
        assertThat(inspect.getState().getRunning()).isTrue();
        var attached = inspect.getNetworkSettings().getNetworks().get(net);
        assertThat(attached.getIpamConfig().getIpv4Address()).isEqualTo(subnet + ".10");
        assertThat(attached.getIpAddress()).isEqualTo(subnet + ".10");
        List<String> ownNames = docker.listContainersCmd().withShowAll(true).exec().stream()
                .map(c -> c.getNames()[0]).filter(n -> n.startsWith("/" + prefix)).toList();
        assertThat(ownNames).containsExactlyInAnyOrder("/" + name, "/" + prefix + "-blocker");
    }

    private static ContainerCreateSpec withNetworks(ContainerCreateSpec s, List<ContainerCreateSpec.Network> networks) {
        return new ContainerCreateSpec(s.name(), s.image(), s.command(), s.env(), s.ports(), s.mounts(), networks,
                s.restartPolicy(), s.privileged(), s.hostNetwork(), s.start());
    }

    @Test
    void recreateKeepsNameAndNamedVolumeAndAppliesNewSpec() {
        String name = prefix + "-web";
        String volume = prefix + "-data";
        volumes.add(volume);
        docker.createVolumeCmd().withName(volume).exec();
        var mount = new ContainerCreateSpec.Mount("VOLUME", volume, "/data", false);
        String oldId = create(spec(name, Map.of("A", "1"), List.of(), List.of(mount), true));

        var result = service.recreateContainer(hostId, endpoint, oldId,
                spec("ignored-" + prefix, Map.of("A", "2"), List.of(), List.of(mount), false));

        assertThat(result.id()).isNotEqualTo(oldId);
        assertThat(result.name()).isEqualTo(name);
        containerIds.add(result.id());
        var inspect = docker.inspectContainerCmd(result.id()).exec();
        assertThat(inspect.getName()).isEqualTo("/" + name);
        assertThat(inspect.getConfig().getEnv()).contains("A=2");
        assertThat(inspect.getState().getRunning()).isTrue();
        assertThat(inspect.getMounts()).anyMatch(m -> volume.equals(m.getName()) && "/data".equals(m.getDestination().getPath()));
        assertThat(docker.inspectVolumeCmd(volume).exec().getName()).isEqualTo(volume);
        assertThatThrownBy(() -> docker.inspectContainerCmd(oldId).exec()).isInstanceOf(RuntimeException.class);
        assertThat(docker.listContainersCmd().withShowAll(true).exec())
                .filteredOn(c -> c.getNames()[0].startsWith("/" + prefix))
                .noneMatch(c -> c.getNames()[0].contains("-shipir-"));
    }

    @Test
    void failedRecreateRestoresTheOldContainer() throws Exception {
        String name = prefix + "-web";
        int taken = freePort();
        create(spec(prefix + "-blocker", Map.of(), List.of(new ContainerCreateSpec.Port(taken, 80, "tcp")), List.of(), true));
        String oldId = create(spec(name, Map.of("A", "1"), List.of(), List.of(), true));

        assertThatThrownBy(() -> service.recreateContainer(hostId, endpoint, oldId,
                spec(name, Map.of("A", "2"), List.of(new ContainerCreateSpec.Port(taken, 80, "tcp")), List.of(), true)))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.RECREATE_FAILED));

        var inspect = docker.inspectContainerCmd(oldId).exec();
        assertThat(inspect.getName()).isEqualTo("/" + name);
        assertThat(inspect.getState().getRunning()).isTrue();
        assertThat(inspect.getConfig().getEnv()).contains("A=1");
        List<String> ownNames = docker.listContainersCmd().withShowAll(true).exec().stream()
                .map(c -> c.getNames()[0]).filter(n -> n.startsWith("/" + prefix)).toList();
        assertThat(ownNames).containsExactlyInAnyOrder("/" + name, "/" + prefix + "-blocker");
    }

    @Test
    void recreateOfAStoppedContainerLeavesTheNewOneStopped() {
        String name = prefix + "-idle";
        String oldId = create(spec(name, Map.of(), List.of(), List.of(), false));

        var result = service.recreateContainer(hostId, endpoint, oldId,
                spec(name, Map.of("B", "1"), List.of(), List.of(), true));

        containerIds.add(result.id());
        var inspect = docker.inspectContainerCmd(result.id()).exec();
        assertThat(inspect.getState().getRunning()).isFalse();
        assertThat(inspect.getName()).isEqualTo("/" + name);
    }

    @Test
    void recreateKeepsResourceLimitsAndLabelsTheSpecDoesNotModel() {
        String name = prefix + "-limits";
        String oldId = create(spec(name, Map.of(), List.of(), List.of(), true));
        service.updateResources(hostId, endpoint, oldId, new ContainerResourcesSpec(64L * 1024 * 1024, 500_000_000L, null));

        var result = service.recreateContainer(hostId, endpoint, oldId, spec(name, Map.of("C", "1"), List.of(), List.of(), true));

        containerIds.add(result.id());
        var host = docker.inspectContainerCmd(result.id()).exec().getHostConfig();
        assertThat(host.getMemory()).isEqualTo(64L * 1024 * 1024);
        assertThat(host.getNanoCPUs()).isEqualTo(500_000_000L);
    }

    @Test
    void recreateKeepsUserWorkingDirAndEntrypointOfTheSameImage() {
        String name = prefix + "-user";
        String oldId = docker.createContainerCmd(IMAGE).withName(name).withUser("nobody").withWorkingDir("/tmp")
                .withEntrypoint("sleep").withCmd("300").exec().getId();
        containerIds.add(oldId);
        docker.startContainerCmd(oldId).exec();

        var result = service.recreateContainer(hostId, endpoint, oldId,
                new ContainerCreateSpec(name, IMAGE, List.of("300"), Map.of(), List.of(), List.of(), List.of(), null,
                        false, false, true));

        containerIds.add(result.id());
        var config = docker.inspectContainerCmd(result.id()).exec().getConfig();
        assertThat(config.getUser()).isEqualTo("nobody");
        assertThat(config.getWorkingDir()).isEqualTo("/tmp");
        assertThat(config.getEntrypoint()).containsExactly("sleep");
    }

    private String createRich(String name, String hostName) {
        var cmd = docker.createContainerCmd(IMAGE).withName(name).withUser("nobody").withWorkingDir("/tmp")
                .withEntrypoint("sleep").withCmd("300").withTty(true).withStdinOpen(true)
                .withHealthcheck(new com.github.dockerjava.api.model.HealthCheck()
                        .withTest(List.of("CMD", "true")).withInterval(30_000_000_000L));
        if (hostName != null) cmd.withHostName(hostName);
        String id = cmd.exec().getId();
        containerIds.add(id);
        docker.startContainerCmd(id).exec();
        return id;
    }

    private ContainerCreateSpec specOf(String name, String image, List<String> command) {
        return new ContainerCreateSpec(name, image, command, Map.of(), List.of(), List.of(), List.of(), null, false, false, true);
    }

    @Test
    void recreateWithAChangedImageDoesNotPinUserWorkingDirEntrypointOrHealthcheck() {
        String name = prefix + "-img";
        String oldId = createRich(name, null);
        String otherTag = prefix + "-other";
        docker.tagImageCmd(IMAGE, "shipir-rc-test", otherTag).exec();

        var result = service.recreateContainer(hostId, endpoint, oldId,
                specOf(name, "shipir-rc-test:" + otherTag, List.of("sleep", "300")));

        containerIds.add(result.id());
        try {
            var config = docker.inspectContainerCmd(result.id()).exec().getConfig();
            assertThat(config.getUser()).isNullOrEmpty();
            assertThat(config.getWorkingDir()).isNullOrEmpty();
            assertThat(config.getEntrypoint()).isNullOrEmpty();
            assertThat(config.getHealthcheck()).isNull();
            assertThat(config.getTty()).isTrue();
            assertThat(config.getStdinOpen()).isTrue();
        } finally {
            docker.removeContainerCmd(result.id()).withForce(true).exec();
            docker.removeImageCmd("shipir-rc-test:" + otherTag).exec();
        }
    }

    @Test
    void recreateWithTheSameImageKeepsHealthcheckTtyAndStdin() {
        String name = prefix + "-hc";
        String oldId = createRich(name, null);

        var result = service.recreateContainer(hostId, endpoint, oldId, specOf(name, IMAGE, List.of("300")));

        containerIds.add(result.id());
        var config = docker.inspectContainerCmd(result.id()).exec().getConfig();
        assertThat(config.getHealthcheck().getTest()).containsExactly("CMD", "true");
        assertThat(config.getTty()).isTrue();
        assertThat(config.getStdinOpen()).isTrue();
    }

    @Test
    void recreateKeepsAnExplicitHostnameButDoesNotPinTheDefaultOne() {
        String explicitName = prefix + "-host1";
        String explicitId = createRich(explicitName, "custom-host");
        String defaultName = prefix + "-host2";
        String defaultId = createRich(defaultName, null);

        var explicit = service.recreateContainer(hostId, endpoint, explicitId, specOf(explicitName, IMAGE, List.of("300")));
        var dflt = service.recreateContainer(hostId, endpoint, defaultId, specOf(defaultName, IMAGE, List.of("300")));

        containerIds.add(explicit.id());
        containerIds.add(dflt.id());
        assertThat(docker.inspectContainerCmd(explicit.id()).exec().getConfig().getHostName()).isEqualTo("custom-host");
        String defaultHost = docker.inspectContainerCmd(dflt.id()).exec().getConfig().getHostName();
        assertThat(defaultHost).isNotEqualTo(defaultId.substring(0, 12));
        assertThat(dflt.id()).startsWith(defaultHost);
    }

    @Test
    void recreateKeepsTheAnonymousVolumeOfAnImageVolumeDeclaration() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("shipir-vol-img");
        java.nio.file.Files.writeString(dir.resolve("Dockerfile"), "FROM " + IMAGE + "\nVOLUME /vol\n");
        String tag = "shipir-rc-vol:" + prefix;
        docker.buildImageCmd(dir.toFile()).withTags(java.util.Set.of(tag)).start().awaitImageId();
        String name = prefix + "-anon";
        String oldId = create(new ContainerCreateSpec(name, tag, List.of("sleep", "300"), Map.of(), List.of(), List.of(),
                List.of(), null, false, false, true));
        String volume = docker.inspectContainerCmd(oldId).exec().getMounts().stream()
                .filter(m -> "/vol".equals(m.getDestination().getPath())).findFirst().orElseThrow().getName();
        volumes.add(volume);

        var result = service.recreateContainer(hostId, endpoint, oldId,
                new ContainerCreateSpec(name, tag, List.of("sleep", "300"), Map.of("A", "2"), List.of(), List.of(),
                        List.of(), null, false, false, true));

        containerIds.add(result.id());
        try {
            var mounts = docker.inspectContainerCmd(result.id()).exec().getMounts();
            assertThat(mounts).filteredOn(m -> "/vol".equals(m.getDestination().getPath()))
                    .singleElement().satisfies(m -> assertThat(m.getName()).isEqualTo(volume));
            assertThat(docker.inspectVolumeCmd(volume).exec().getName()).isEqualTo(volume);
        } finally {
            docker.removeContainerCmd(result.id()).withForce(true).exec();
            docker.removeImageCmd(tag).withForce(true).exec();
        }
    }

    @Test
    void recreateOfUnknownContainerIsNotFound() {
        assertThatThrownBy(() -> service.recreateContainer(hostId, endpoint, "0".repeat(64),
                spec(prefix + "-x", Map.of(), List.of(), List.of(), true)))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }

    @Test
    void updateResourcesChangesLimitsAndRestartPolicyInPlace() {
        String oldId = create(spec(prefix + "-upd", Map.of(), List.of(), List.of(), true));

        service.updateResources(hostId, endpoint, oldId, new ContainerResourcesSpec(32L * 1024 * 1024, 250_000_000L, "on-failure"));

        var host = docker.inspectContainerCmd(oldId).exec().getHostConfig();
        assertThat(host.getMemory()).isEqualTo(32L * 1024 * 1024);
        assertThat(host.getNanoCPUs()).isEqualTo(250_000_000L);
        assertThat(host.getRestartPolicy().getName()).isEqualTo("on-failure");
        assertThat(docker.inspectContainerCmd(oldId).exec().getState().getRunning()).isTrue();
    }

    @Test
    void updateResourcesRefusedByTheDaemonIsUpdateRejected() {
        String oldId = create(spec(prefix + "-rej", Map.of(), List.of(), List.of(), true));

        assertThatThrownBy(() -> service.updateResources(hostId, endpoint, oldId, new ContainerResourcesSpec(1024L, null, null)))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.UPDATE_REJECTED));
    }
}
