package dev.bethge.shipir.host;

import com.github.dockerjava.api.DockerClient;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.client.VolumeArchiveServiceImpl;
import dev.bethge.shipir.permission.CurrentAccess;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import org.testcontainers.DockerClientFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Restore from a stored archive against a real daemon and the real restore pipeline: round trip, in-use guard and
 * hostile archives (nothing may be written into the volume).
 */
class VolumeBackupStoredRestoreIntegrationTest {

    private static final String IMAGE = "alpine:3";

    @TempDir
    Path base;

    private final UUID hostId = UUID.randomUUID();
    private final List<String> volumes = new ArrayList<>();
    private final List<String> containers = new ArrayList<>();
    private DockerHostConnectionManager manager;
    private DockerClient docker;
    private VolumeArchiveServiceImpl archives;
    private VolumeBackupService service;
    private VolumeBackupStore store;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(30)));
        docker = DockerClientFactory.instance().client();
        try {
            docker.inspectImageCmd(IMAGE).exec();
        } catch (Exception notPresent) {
            docker.pullImageCmd(IMAGE).start().awaitCompletion();
        }
        archives = new VolumeArchiveServiceImpl(manager);

        DockerHost host = mock(DockerHost.class);
        when(host.getId()).thenReturn(hostId);
        DockerHostRepository hosts = mock(DockerHostRepository.class);
        when(hosts.findById(hostId)).thenReturn(Optional.of(host));
        HostEndpointResolver endpoints = mock(HostEndpointResolver.class);
        DockerEndpoint endpoint = manager.localEndpoint();
        when(endpoints.endpointOf(any())).thenReturn(endpoint);
        CurrentAccess access = mock(CurrentAccess.class);
        when(access.canView(hostId)).thenReturn(true);
        when(access.canOperate(hostId)).thenReturn(true);

        VolumeBackupProperties props = new VolumeBackupProperties(DataSize.ofMegabytes(10), IMAGE, base, false, Duration.ofMinutes(1));
        store = new VolumeBackupStore(props, Clock.systemUTC());
        service = new VolumeBackupService(hosts, endpoints, archives, store, access, props, Clock.systemUTC(), mock(SystemManagedGuard.class));
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
        String name = "shipir-it-stored-" + UUID.randomUUID().toString().substring(0, 8);
        docker.createVolumeCmd().withName(name).exec();
        volumes.add(name);
        return name;
    }

    private String store(String volume, byte[] tar) throws Exception {
        String name = volume + "-20260102T030405Z.tar";
        Path dir = Files.createDirectories(base.resolve(hostId.toString()).resolve(volume));
        Files.write(dir.resolve(name), tar);
        return name;
    }

    private List<String> entryNames(String volume) throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = service.backup(hostId, volume).content();
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            for (TarArchiveEntry e = tar.getNextEntry(); e != null; e = tar.getNextEntry()) {
                names.add(e.getName().replaceFirst("^\\./", "").replaceFirst("/$", ""));
            }
        }
        return names;
    }

    private static byte[] tar(Consumer<TarArchiveOutputStream> fill) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream t = new TarArchiveOutputStream(out)) {
            t.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            fill.accept(t);
        }
        return out.toByteArray();
    }

    private static void file(TarArchiveOutputStream t, String name, String content) {
        try {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry e = new TarArchiveEntry(name, true); // keep a leading slash as an attacker would
            e.setSize(data.length);
            t.putArchiveEntry(e);
            t.write(data);
            t.closeArchiveEntry();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void symlink(TarArchiveOutputStream t, String name, String target) {
        try {
            TarArchiveEntry e = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
            e.setLinkName(target);
            t.putArchiveEntry(e);
            t.closeArchiveEntry();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long helperCount() {
        return docker.listContainersCmd().withShowAll(true).withLabelFilter(List.of("dev.bethge.shipir.helper")).exec().size();
    }

    @Test
    void storedArchiveRoundTripsIntoAnUnusedVolume() throws Exception {
        String volume = newVolume();
        String name = store(volume, tar(t -> file(t, "restored.txt", "hello")));

        service.restoreStored(hostId, volume, name, false);

        assertThat(entryNames(volume)).contains("restored.txt");
        assertThat(helperCount()).isZero();
    }

    @Test
    void inUseVolumeIsRefusedWithoutConfirmAndNothingIsWritten() throws Exception {
        String volume = newVolume();
        String name = store(volume, tar(t -> file(t, "restored.txt", "hello")));
        var user = docker.createContainerCmd(IMAGE).withCmd("sleep", "300")
                .withHostConfig(com.github.dockerjava.api.model.HostConfig.newHostConfig()
                        .withBinds(new com.github.dockerjava.api.model.Bind(volume, new com.github.dockerjava.api.model.Volume("/data")))).exec();
        containers.add(user.getId());

        assertThatThrownBy(() -> service.restoreStored(hostId, volume, name, false))
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("VOLUME_IN_USE"));
        assertThat(entryNames(volume)).doesNotContain("restored.txt");

        service.restoreStored(hostId, volume, name, true);
        assertThat(entryNames(volume)).contains("restored.txt");
    }

    @Test
    void hostileStoredArchivesAreRejectedAndNothingIsWritten() throws Exception {
        List<byte[]> hostile = List.of(
                tar(t -> { file(t, "ok.txt", "fine"); file(t, "../evil.txt", "x"); }),
                tar(t -> { file(t, "ok.txt", "fine"); file(t, "/etc/evil.txt", "x"); }),
                tar(t -> { file(t, "ok.txt", "fine"); symlink(t, "link", "../../etc"); }),
                tar(t -> { symlink(t, "link", "/etc"); file(t, "link/evil.txt", "x"); }));
        for (byte[] archive : hostile) {
            String volume = newVolume();
            String name = store(volume, archive);

            assertThatThrownBy(() -> service.restoreStored(hostId, volume, name, true))
                    .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("INVALID_ARCHIVE"));
            assertThat(entryNames(volume)).doesNotContain("ok.txt", "evil.txt", "link", "link/evil.txt");
            assertThat(helperCount()).isZero();
        }
    }
}
