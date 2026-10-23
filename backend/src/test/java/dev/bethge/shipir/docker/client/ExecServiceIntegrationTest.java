package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Interactive exec against a real busybox container on the local Docker daemon. */
class ExecServiceIntegrationTest {

    private static final String IMAGE = "busybox:1.36";

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerEndpoint endpoint;
    private DockerClient docker;
    private ExecServiceImpl service;
    private String id;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(5)));
        endpoint = manager.localEndpoint();
        docker = DockerClientFactory.instance().client();
        try {
            docker.inspectImageCmd(IMAGE).exec();
        } catch (Exception notPresent) {
            docker.pullImageCmd(IMAGE).start().awaitCompletion();
        }
        service = new ExecServiceImpl(manager);
        id = docker.createContainerCmd(IMAGE).withName("shipir-it-exec-" + UUID.randomUUID().toString().substring(0, 8))
                .withCmd("sleep", "300").exec().getId();
        docker.startContainerCmd(id).exec();
    }

    @AfterEach
    void tearDown() {
        if (id != null) docker.removeContainerCmd(id).withForce(true).exec();
        if (manager != null) manager.evict(hostId);
    }

    /** Collects terminal output and lets tests wait for a marker. */
    private static final class Output {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        synchronized void accept(byte[] bytes) {
            buffer.writeBytes(bytes);
            notifyAll();
        }

        synchronized boolean awaitContains(String text, long millis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + millis;
            while (!buffer.toString(StandardCharsets.UTF_8).contains(text)) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false;
                wait(left);
            }
            return true;
        }

        synchronized String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void echoRoundTripOverTty() throws Exception {
        Output out = new Output();
        try (ExecHandle handle = service.open(hostId, endpoint, id, List.of("sh"), 80, 24, out::accept)) {
            handle.write(bytes("echo hello-$((20+22))\n"));
            assertThat(out.awaitContains("hello-42", 10_000)).as(out.text()).isTrue();
        }
    }

    @Test
    void resizeIsAppliedToTheTty() throws Exception {
        Output out = new Output();
        try (ExecHandle handle = service.open(hostId, endpoint, id, List.of("sh"), 80, 24, out::accept)) {
            handle.resize(123, 45);
            handle.write(bytes("echo size=$(stty size)\n"));
            assertThat(out.awaitContains("size=45 123", 10_000)).as(out.text()).isTrue();
        }
    }

    @Test
    void closeTerminatesTheExecProcessAndReportsEnd() throws Exception {
        Output out = new Output();
        CountDownLatch ended = new CountDownLatch(1);
        AtomicReference<Throwable> endError = new AtomicReference<>();
        ExecHandle handle = service.open(hostId, endpoint, id, List.of("sh", "-c", "echo marker-$$; exec sleep 250"),
                80, 24, out::accept, e -> { endError.set(e); ended.countDown(); });
        assertThat(out.awaitContains("marker-", 10_000)).isTrue();
        assertThat(countProcesses("sleep 250")).isEqualTo(1);

        handle.close();
        handle.close(); // idempotent

        assertThat(ended.await(10, TimeUnit.SECONDS)).isTrue();
        long deadline = System.currentTimeMillis() + 10_000;
        while (countProcesses("sleep 250") > 0 && System.currentTimeMillis() < deadline) Thread.sleep(200);
        assertThat(countProcesses("sleep 250")).as("exec process gone after close").isZero();
        handle.write(bytes("ignored\n")); // no exception after close
    }

    @Test
    void processExitEndsTheSession() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        try (ExecHandle handle = service.open(hostId, endpoint, id, List.of("sh"), 80, 24, b -> { }, e -> ended.countDown())) {
            handle.write(bytes("exit\n"));
            assertThat(ended.await(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void notRunningContainerIsMappedToInvalidState() {
        docker.stopContainerCmd(id).withTimeout(0).exec();
        assertThatThrownBy(() -> service.open(hostId, endpoint, id, List.of("sh"), 80, 24, b -> { }))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_STATE));
    }

    @Test
    void unknownContainerIsMappedToNotFound() {
        assertThatThrownBy(() -> service.open(hostId, endpoint, "does-not-exist-" + UUID.randomUUID(), List.of("sh"), 80, 24, b -> { }))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }

    private int countProcesses(String commandLine) throws Exception {
        var exec = docker.execCreateCmd(id).withAttachStdout(true).withCmd("ps", "-o", "args").exec();
        var sink = new com.github.dockerjava.api.async.ResultCallback.Adapter<com.github.dockerjava.api.model.Frame>() {
            final StringBuilder sb = new StringBuilder();
            @Override public void onNext(com.github.dockerjava.api.model.Frame f) { sb.append(new String(f.getPayload(), StandardCharsets.UTF_8)); }
        };
        docker.execStartCmd(exec.getId()).exec(sink).awaitCompletion(10, TimeUnit.SECONDS);
        return (int) sink.sb.toString().lines().filter(l -> l.trim().equals(commandLine)).count();
    }
}
