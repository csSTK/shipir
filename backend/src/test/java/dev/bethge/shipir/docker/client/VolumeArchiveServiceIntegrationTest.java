package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.DockerClientFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Backup and restore against a real daemon: round trip, in-use guard, helper cleanup, hostile input. */
class VolumeArchiveServiceIntegrationTest {

    private static final String IMAGE = "alpine:3";
    private static final String HELPER_LABEL = "dev.bethge.shipir.helper";
    private static final long LIMIT = 50_000_000;

    private final UUID hostId = UUID.randomUUID();
    private final List<String> volumes = new ArrayList<>();
    private final List<String> containers = new ArrayList<>();
    private DockerHostConnectionManager manager;
    private DockerEndpoint endpoint;
    private DockerClient docker;
    private VolumeArchiveServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(30)));
        endpoint = manager.localEndpoint();
        docker = DockerClientFactory.instance().client();
        try {
            docker.inspectImageCmd(IMAGE).exec();
        } catch (Exception notPresent) {
            docker.pullImageCmd(IMAGE).start().awaitCompletion();
        }
        service = new VolumeArchiveServiceImpl(manager);
    }

    @AfterEach
    void tearDown() {
        if (docker == null) return;
        for (String c : containers) {
            try {
                docker.removeContainerCmd(c).withForce(true).exec();
            } catch (Exception ignored) {
                // already gone
            }
        }
        for (String v : volumes) {
            try {
                docker.removeVolumeCmd(v).exec();
            } catch (Exception ignored) {
                // already gone
            }
        }
        if (manager != null) manager.evict(hostId);
    }

    private String newVolume() {
        String name = "shipir-it-vol-" + UUID.randomUUID().toString().substring(0, 8);
        docker.createVolumeCmd().withName(name).exec();
        volumes.add(name);
        return name;
    }

    /** Runs a shell script in a throwaway container with the volume at /data and waits for it. */
    private void run(String volume, String script) throws Exception {
        CreateContainerResponse c = docker.createContainerCmd(IMAGE).withCmd("sh", "-c", script)
                .withHostConfig(HostConfig.newHostConfig().withBinds(new Bind(volume, new Volume("/data")))).exec();
        containers.add(c.getId());
        docker.startContainerCmd(c.getId()).exec();
        Integer code = docker.waitContainerCmd(c.getId()).start().awaitStatusCode();
        assertThat(code).isZero();
    }

    private byte[] backup(String volume) throws IOException {
        try (InputStream in = service.backup(hostId, endpoint, volume, IMAGE, LIMIT)) {
            return in.readAllBytes();
        }
    }

    private static Map<String, TarArchiveEntry> entries(byte[] tar, Map<String, String> contentsOut) throws IOException {
        Map<String, TarArchiveEntry> result = new HashMap<>();
        try (TarArchiveInputStream in = new TarArchiveInputStream(new ByteArrayInputStream(tar))) {
            for (TarArchiveEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                String name = e.getName().replaceFirst("^\\./", "").replaceFirst("/$", "");
                result.put(name, e);
                if (e.isFile() && contentsOut != null) {
                    contentsOut.put(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return result;
    }

    private long helperCount() {
        return docker.listContainersCmd().withShowAll(true).withLabelFilter(List.of(HELPER_LABEL)).exec().size();
    }

    private static byte[] tarOf(String name, String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry e = new TarArchiveEntry(name);
            e.setSize(data.length);
            tar.putArchiveEntry(e);
            tar.write(data);
            tar.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    @Test
    void roundTripPreservesFilesPermissionsOwnerAndSymlinks() throws Exception {
        String source = newVolume();
        run(source, "echo hello > /data/a.txt && chmod 640 /data/a.txt && chown 1234:5678 /data/a.txt"
                + " && mkdir -p /data/sub && echo nested > /data/sub/b.txt && chmod 700 /data/sub"
                + " && ln -s sub/b.txt /data/link && chmod 755 /data");
        byte[] archive = backup(source);
        assertThat(helperCount()).isZero();

        String target = newVolume();
        service.restore(hostId, endpoint, target, IMAGE, () -> new ByteArrayInputStream(archive), LIMIT, false);
        assertThat(helperCount()).isZero();

        Map<String, String> contents = new HashMap<>();
        Map<String, TarArchiveEntry> restored = entries(backup(target), contents);
        assertThat(contents).containsEntry("a.txt", "hello\n").containsEntry("sub/b.txt", "nested\n");
        assertThat(restored.get("a.txt").getMode() & 07777).isEqualTo(0640);
        assertThat(restored.get("a.txt").getLongUserId()).isEqualTo(1234);
        assertThat(restored.get("a.txt").getLongGroupId()).isEqualTo(5678);
        assertThat(restored.get("sub").getMode() & 07777).isEqualTo(0700);
        assertThat(restored.get("link").isSymbolicLink()).isTrue();
        assertThat(restored.get("link").getLinkName()).isEqualTo("sub/b.txt");
    }

    @Test
    void restoreIntoInUseVolumeIsRefusedWithoutConfirmAndAllowedWithIt() throws Exception {
        String volume = newVolume();
        CreateContainerResponse user = docker.createContainerCmd(IMAGE).withCmd("sleep", "300")
                .withHostConfig(HostConfig.newHostConfig().withBinds(new Bind(volume, new Volume("/data")))).exec();
        containers.add(user.getId());
        byte[] archive = tarOf("restored.txt", "new");

        assertThatThrownBy(() -> service.restore(hostId, endpoint, volume, IMAGE,
                () -> new ByteArrayInputStream(archive), LIMIT, false))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.IN_USE));
        assertThat(entries(backup(volume), null)).doesNotContainKey("restored.txt");
        assertThat(helperCount()).isZero();

        service.restore(hostId, endpoint, volume, IMAGE, () -> new ByteArrayInputStream(archive), LIMIT, true);
        assertThat(entries(backup(volume), null)).containsKey("restored.txt");
    }

    @Test
    void containersCarryingTheHelperLabelDoNotCountAsUsers() throws Exception {
        String volume = newVolume();
        CreateContainerResponse helper = docker.createContainerCmd(IMAGE).withCmd("true")
                .withLabels(Map.of(HELPER_LABEL, "volume-backup"))
                .withHostConfig(HostConfig.newHostConfig().withBinds(new Bind(volume, new Volume("/data")))).exec();
        containers.add(helper.getId());
        byte[] archive = tarOf("restored.txt", "new");

        service.restore(hostId, endpoint, volume, IMAGE, () -> new ByteArrayInputStream(archive), LIMIT, false);

        assertThat(entries(backup(volume), null)).containsKey("restored.txt");
    }

    @Test
    void helperContainerIsRemovedWhenRestoreFailsAfterItWasCreated() throws Exception {
        String volume = newVolume();
        byte[] archive = tarOf("a.txt", "x");
        int[] opens = {0};

        assertThatThrownBy(() -> service.restore(hostId, endpoint, volume, IMAGE, () -> {
            if (++opens[0] > 1) throw new IOException("upload vanished");
            return new ByteArrayInputStream(archive);
        }, LIMIT, false)).isInstanceOf(DockerConnectionException.class);

        assertThat(opens[0]).isEqualTo(2);
        assertThat(helperCount()).isZero();
    }

    @Test
    void helperContainerIsRemovedWhenTheBackupStreamIsClosedEarly() throws Exception {
        String volume = newVolume();
        run(volume, "echo hello > /data/a.txt");

        InputStream in = service.backup(hostId, endpoint, volume, IMAGE, LIMIT);
        assertThat(helperCount()).isEqualTo(1);
        in.read();
        in.close();

        assertThat(helperCount()).isZero();
    }

    @Test
    void helperIsLockedDown() throws Exception {
        String volume = newVolume();
        InputStream in = service.backup(hostId, endpoint, volume, IMAGE, LIMIT);
        try {
            var helper = docker.listContainersCmd().withShowAll(true).withLabelFilter(List.of(HELPER_LABEL)).exec().get(0);
            var inspect = docker.inspectContainerCmd(helper.getId()).exec();
            assertThat(inspect.getHostConfig().getPrivileged()).isNotEqualTo(Boolean.TRUE);
            assertThat(inspect.getHostConfig().getNetworkMode()).isEqualTo("none");
            assertThat(inspect.getMounts()).hasSize(1);
            assertThat(inspect.getMounts().get(0).getName()).isEqualTo(volume);
            assertThat(inspect.getMounts().get(0).getRW()).isFalse();
            assertThat(inspect.getState().getRunning()).isNotEqualTo(Boolean.TRUE);
        } finally {
            in.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc", "/var/run/docker.sock", "/", "a:b", "a/b", "", " ", "-lead", "a b", "a;rm -rf /",
            "$(id)", "name\u0000x", "a\nb", ".hidden"})
    void badVolumeNamesAreRefusedBeforeAnyContainerIsCreated(String name) {
        long before = helperCount();
        assertThatThrownBy(() -> service.backup(hostId, endpoint, name, IMAGE, LIMIT))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_VOLUME_NAME));
        assertThatThrownBy(() -> service.restore(hostId, endpoint, name, IMAGE,
                () -> new ByteArrayInputStream(new byte[0]), LIMIT, true))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_VOLUME_NAME));
        assertThat(helperCount()).isEqualTo(before);
    }

    @Test
    void overlongVolumeNameIsRefused() {
        assertThatThrownBy(() -> service.backup(hostId, endpoint, "a".repeat(129), IMAGE, LIMIT))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_VOLUME_NAME));
    }

    @Test
    void missingVolumeIsNotFoundAndRestoreNeverCreatesIt() throws Exception {
        String name = "shipir-it-missing-" + UUID.randomUUID().toString().substring(0, 8);
        volumes.add(name);

        assertThatThrownBy(() -> service.backup(hostId, endpoint, name, IMAGE, LIMIT))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
        byte[] archive = tarOf("a.txt", "x");
        assertThatThrownBy(() -> service.restore(hostId, endpoint, name, IMAGE,
                () -> new ByteArrayInputStream(archive), LIMIT, true))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
        assertThat(docker.listVolumesCmd().exec().getVolumes()).extracting(v -> v.getName()).doesNotContain(name);
        assertThat(helperCount()).isZero();
    }

    @Test
    void traversalArchiveIsRefusedAndLeavesTheVolumeUntouched() throws Exception {
        String volume = newVolume();
        run(volume, "echo keep > /data/keep.txt");
        byte[] evil = tarOf("../escape.txt", "pwn");

        assertThatThrownBy(() -> service.restore(hostId, endpoint, volume, IMAGE,
                () -> new ByteArrayInputStream(evil), LIMIT, true))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_ARCHIVE));
        Map<String, String> contents = new HashMap<>();
        entries(backup(volume), contents);
        assertThat(contents).containsOnlyKeys("keep.txt");
        assertThat(helperCount()).isZero();
    }

    @Test
    void oversizeRestoreIsRefusedBeforeExtraction() throws Exception {
        String volume = newVolume();
        byte[] big = tarOf("big.bin", "x".repeat(20_000));

        assertThatThrownBy(() -> service.restore(hostId, endpoint, volume, IMAGE,
                () -> new ByteArrayInputStream(big), 4096, true))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.SIZE_LIMIT_EXCEEDED));
        assertThat(entries(backup(volume), null)).doesNotContainKey("big.bin");
    }

    @Test
    void backupBeyondTheLimitFailsWhileReading() throws Exception {
        String volume = newVolume();
        run(volume, "head -c 200000 /dev/zero > /data/big.bin");

        try (InputStream in = service.backup(hostId, endpoint, volume, IMAGE, 10_000)) {
            assertThatThrownBy(in::readAllBytes).isInstanceOfSatisfying(DockerConnectionException.class,
                    e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.SIZE_LIMIT_EXCEEDED));
        }
        assertThat(helperCount()).isZero();
    }
}
