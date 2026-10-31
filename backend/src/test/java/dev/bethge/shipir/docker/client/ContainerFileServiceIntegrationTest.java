package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.ContainerFileEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** File transfer against a real container on the local Docker daemon. */
class ContainerFileServiceIntegrationTest {

    private static final String IMAGE = "alpine:3.20";

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerEndpoint endpoint;
    private DockerClient docker;
    private ContainerFileServiceImpl service;
    private String id;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(15)));
        endpoint = manager.localEndpoint();
        docker = DockerClientFactory.instance().client();
        try {
            docker.inspectImageCmd(IMAGE).exec();
        } catch (Exception notPresent) {
            docker.pullImageCmd(IMAGE).start().awaitCompletion();
        }
        id = docker.createContainerCmd(IMAGE).withName("shipir-it-files-" + UUID.randomUUID().toString().substring(0, 8))
                .withCmd("sh", "-c", "mkdir -p /data/sub && echo hello > /data/a.txt && echo nested > /data/sub/b.txt"
                        + " && ln -s /data/sub /data/link && head -c 5000 /dev/zero > /data/big.bin && sleep 300")
                .exec().getId();
        docker.startContainerCmd(id).exec();
        Thread.sleep(800);
        service = new ContainerFileServiceImpl(manager, new ContainerDirectoryExecLister(manager));
    }

    @AfterEach
    void tearDown() {
        if (id != null) docker.removeContainerCmd(id).withForce(true).exec();
        if (manager != null) manager.evict(hostId);
    }

    @Test
    void listsDirectChildrenDirectoriesFirst() {
        List<ContainerFileEntry> entries = service.list(hostId, endpoint, id, "/data/", 10_000_000);

        assertThat(entries).extracting(ContainerFileEntry::name).containsExactly("sub", "a.txt", "big.bin", "link");
        ContainerFileEntry a = entries.stream().filter(e -> e.name().equals("a.txt")).findFirst().orElseThrow();
        assertThat(a.type()).isEqualTo(ContainerFileEntry.Type.FILE);
        assertThat(a.size()).isEqualTo(6);
        assertThat(a.path()).isEqualTo("/data/a.txt");
        assertThat(a.modified()).isNotNull();
        ContainerFileEntry link = entries.stream().filter(e -> e.name().equals("link")).findFirst().orElseThrow();
        assertThat(link.type()).isEqualTo(ContainerFileEntry.Type.SYMLINK);
        assertThat(link.linkTarget()).isEqualTo("/data/sub");
    }

    @Test
    void listFollowsSymlinkToDirectoryAndListsRoot() {
        assertThat(service.list(hostId, endpoint, id, "/data/link", 10_000_000)).extracting(ContainerFileEntry::name)
                .containsExactly("b.txt");
        assertThat(service.list(hostId, endpoint, id, "/", 100_000_000)).extracting(ContainerFileEntry::name)
                .contains("etc", "data", "bin");
    }

    @Test
    void listOfFileOrMissingPathFails() {
        assertThatThrownBy(() -> service.list(hostId, endpoint, id, "/data/a.txt", 10_000_000))
                .isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_PATH));
        assertThatThrownBy(() -> service.list(hostId, endpoint, id, "/nope", 10_000_000))
                .isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }

    @Test
    void listOverScanLimitFallsBackToExec() {
        List<ContainerFileEntry> entries = service.list(hostId, endpoint, id, "/data", 3000);
        assertThat(entries).extracting(ContainerFileEntry::name).containsExactly("sub", "a.txt", "big.bin", "link");
        assertThat(entries.get(0).type()).isEqualTo(ContainerFileEntry.Type.DIRECTORY);
        assertThat(entries.get(3).type()).isEqualTo(ContainerFileEntry.Type.SYMLINK);
        // a symlink to a directory is followed by the fallback as well
        assertThat(service.list(hostId, endpoint, id, "/data/link", 100)).extracting(ContainerFileEntry::name)
                .containsExactly("b.txt");
    }

    @Test
    void listRootWithTinyLimitSucceeds() {
        assertThat(service.list(hostId, endpoint, id, "/", 1000)).extracting(ContainerFileEntry::name)
                .contains("etc", "data", "bin");
    }

    @Test
    void execFallbackHandlesOddNamesAndTraversalInput() throws Exception {
        exec("sh", "-c", "cd /data && touch -- '-rf' '--help' \"$(printf 'a\\nb')\"");
        List<ContainerFileEntry> entries = service.list(hostId, endpoint, id, "/data/sub/../../data/", 100);
        assertThat(entries).extracting(ContainerFileEntry::name).contains("-rf", "--help", "a\nb", "a.txt");
        assertThat(service.list(hostId, endpoint, id, "/../../data", 100)).extracting(ContainerFileEntry::name)
                .contains("-rf");
    }

    @Test
    void execFailureOnImageWithoutFindIsExecUnavailable() throws Exception {
        exec("rm", "/usr/bin/find");
        assertThatThrownBy(() -> service.list(hostId, endpoint, id, "/data", 3000))
                .isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.DIRECTORY_EXEC_UNAVAILABLE));
    }

    @Test
    void execFallbackWorksWithGnuFindOnDebian() throws Exception {
        String debian = "debian:12-slim";
        try {
            docker.inspectImageCmd(debian).exec();
        } catch (Exception notPresent) {
            docker.pullImageCmd(debian).start().awaitCompletion();
        }
        String debianId = docker.createContainerCmd(debian).withCmd("sleep", "120").exec().getId();
        try {
            docker.startContainerCmd(debianId).exec();
            List<ContainerFileEntry> entries = service.list(hostId, endpoint, debianId, "/", 1000);
            assertThat(entries).extracting(ContainerFileEntry::name).contains("etc", "usr", "bin");
            assertThat(entries).filteredOn(e -> e.name().equals("etc")).extracting(ContainerFileEntry::type)
                    .containsExactly(ContainerFileEntry.Type.DIRECTORY);
            // /bin is a symlink to usr/bin on debian 12; listing it follows the link
            assertThat(service.list(hostId, endpoint, debianId, "/bin", 1000)).extracting(ContainerFileEntry::name)
                    .contains("sh", "ls");
        } finally {
            docker.removeContainerCmd(debianId).withForce(true).exec();
        }
    }

    private void exec(String... cmd) throws Exception {
        String execId = docker.execCreateCmd(id).withCmd(cmd).exec().getId();
        docker.execStartCmd(execId).start().awaitCompletion();
    }

    @Test
    void downloadReturnsTarOfFile() throws Exception {
        try (InputStream in = service.download(hostId, endpoint, id, "/data/a.txt", 1_000_000);
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertThat(entry.getName()).isEqualTo("a.txt");
            assertThat(new String(tar.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello\n");
        }
    }

    @Test
    void downloadLimitIsEnforcedWhileStreaming() throws Exception {
        try (InputStream in = service.download(hostId, endpoint, id, "/data/big.bin", 2000)) {
            assertThatThrownBy(in::readAllBytes).isInstanceOfSatisfying(DockerConnectionException.class,
                    e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.SIZE_LIMIT_EXCEEDED));
        }
    }

    @Test
    void downloadOfMissingPathIsNotFound() {
        assertThatThrownBy(() -> service.download(hostId, endpoint, id, "/nope", 1000))
                .isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }

    @Test
    void uploadExtractsIntoDirectory() throws Exception {
        service.upload(hostId, endpoint, id, "/data/sub", tarOf("up.txt", "uploaded".getBytes(StandardCharsets.UTF_8)), 1_000_000);

        try (InputStream in = service.download(hostId, endpoint, id, "/data/sub/up.txt", 1_000_000);
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            tar.getNextEntry();
            assertThat(new String(tar.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("uploaded");
        }
    }

    @Test
    void uploadLimitIsEnforcedWhileStreaming() throws Exception {
        InputStream big = tarOf("huge.bin", new byte[200_000]);

        assertThatThrownBy(() -> service.upload(hostId, endpoint, id, "/data/sub", big, 10_000))
                .isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void uploadToMissingDirectoryIsNotFound() throws Exception {
        assertThatThrownBy(() -> service.upload(hostId, endpoint, id, "/nope", tarOf("x", new byte[1]), 1_000_000))
                .isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }

    private static InputStream tarOf(String name, byte[] content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return new ByteArrayInputStream(out.toByteArray());
    }
}
