package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.BuildSpec;
import dev.bethge.shipir.docker.dto.ImageDto;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real build and tag against the local daemon (base image comes from a registry); skipped without Docker. */
class ImageBuildIntegrationTest {

    private final UUID hostId = UUID.randomUUID();
    private final String name = "shipir-build-it-" + UUID.randomUUID().toString().substring(0, 8);
    private DockerHostConnectionManager manager;
    private ImageServiceImpl service;
    private DockerEndpoint endpoint;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(20)));
        endpoint = manager.localEndpoint();
        service = new ImageServiceImpl(manager);
    }

    @AfterEach
    void tearDown() {
        if (manager == null) return;
        try {
            for (ImageDto image : service.list(hostId, endpoint)) {
                if (image.repoTags().stream().anyMatch(t -> t.contains(name))) {
                    manager.client(hostId, endpoint).removeImageCmd(image.id()).withForce(true).exec();
                }
            }
        } catch (RuntimeException ignored) {
            // best-effort cleanup
        }
        manager.evict(hostId);
    }

    private static byte[] context(String dockerfile) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            byte[] data = dockerfile.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry e = new TarArchiveEntry("Dockerfile");
            e.setSize(data.length);
            tar.putArchiveEntry(e);
            tar.write(data);
            tar.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    private BuildSpec spec(String tag, Map<String, String> args) {
        return new BuildSpec("Dockerfile", tag, args, true, 10 * 1024 * 1024, Duration.ofMinutes(5));
    }

    @Test
    void buildsABusyboxImageStreamsOutputAndTagsIt() throws Exception {
        byte[] ctx = context("FROM busybox:1.36\nARG GREETING=hi\nRUN echo built-$GREETING > /greeting\nCMD [\"cat\", \"/greeting\"]\n");
        List<String> lines = new ArrayList<>();
        service.build(hostId, endpoint, () -> new ByteArrayInputStream(ctx), spec(name + ":v1", Map.of("GREETING", "marker42")),
                List.of(), lines::add);

        assertThat(lines).isNotEmpty();
        assertThat(lines).anyMatch(l -> l.contains("Step 1/") || l.contains("FROM"));
        assertThat(service.list(hostId, endpoint)).anyMatch(i -> i.repoTags().contains(name + ":v1"));

        ImageDto built = service.list(hostId, endpoint).stream()
                .filter(i -> i.repoTags().contains(name + ":v1")).findFirst().orElseThrow();
        service.tag(hostId, endpoint, built.id(), name, "v2");
        assertThat(service.list(hostId, endpoint))
                .anyMatch(i -> i.repoTags().containsAll(List.of(name + ":v1", name + ":v2")));
    }

    @Test
    void failingStepIsBuildFailedWithoutDaemonText() throws Exception {
        byte[] ctx = context("FROM busybox:1.36\nRUN exit 3\n");
        assertThatThrownBy(() -> service.build(hostId, endpoint, () -> new ByteArrayInputStream(ctx),
                spec(name + ":bad", Map.of()), List.of(), l -> { }))
                .isInstanceOfSatisfying(DockerConnectionException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(DockerErrorCode.BUILD_FAILED);
                    assertThat(e.getMessage()).doesNotContain("exit 3").doesNotContain("returned a non-zero code");
                });
    }

    @Test
    void hostileContextIsRefusedBeforeTheDaemonSeesIt() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            TarArchiveEntry e = new TarArchiveEntry("../escape", true);
            e.setSize(1);
            tar.putArchiveEntry(e);
            tar.write(1);
            tar.closeArchiveEntry();
        }
        byte[] ctx = out.toByteArray();
        assertThatThrownBy(() -> service.build(hostId, endpoint, () -> new ByteArrayInputStream(ctx),
                spec(name + ":evil", Map.of()), List.of(), l -> { }))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_ARCHIVE));
    }

    @Test
    void taggingAnUnknownImageIsNotFound() {
        assertThatThrownBy(() -> service.tag(hostId, endpoint, "sha256:" + "0".repeat(64), name, "x"))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }

    @Test
    void pushingToAnUnreachableRegistryFailsWithPushFailed() throws Exception {
        byte[] ctx = context("FROM busybox:1.36\nCMD [\"true\"]\n");
        String ref = "127.0.0.1:1/" + name + ":push";
        service.build(hostId, endpoint, () -> new ByteArrayInputStream(ctx), spec(ref, Map.of()), List.of(), l -> { });
        assertThatThrownBy(() -> service.push(hostId, endpoint, ref, null, l -> { }))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isIn(DockerErrorCode.PUSH_FAILED, DockerErrorCode.UNREACHABLE));
    }
}
