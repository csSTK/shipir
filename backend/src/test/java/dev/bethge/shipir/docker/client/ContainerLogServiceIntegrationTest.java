package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.LogLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Follows real container output on the local Docker daemon. */
class ContainerLogServiceIntegrationTest {

    private static final String IMAGE = "alpine:3.20";

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerEndpoint endpoint;
    private DockerClient docker;
    private ContainerLogServiceImpl service;
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
        service = new ContainerLogServiceImpl(manager);
    }

    @AfterEach
    void tearDown() {
        if (id != null) docker.removeContainerCmd(id).withForce(true).exec();
        if (manager != null) manager.evict(hostId);
    }

    private void startContainer(boolean tty, String script) {
        id = docker.createContainerCmd(IMAGE).withName("shipir-it-log-" + UUID.randomUUID().toString().substring(0, 8))
                .withTty(tty).withCmd("sh", "-c", script).exec().getId();
        docker.startContainerCmd(id).exec();
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(15, TimeUnit.SECONDS)).as("expected log lines arrived").isTrue();
    }

    @Test
    void tailThenFollowDeliversInOrderWithStreamMarker() throws Exception {
        startContainer(false, "echo old1; echo old2; echo oldErr >&2; sleep 2; echo new1; echo newErr >&2; sleep 300");
        List<LogLine> lines = new CopyOnWriteArrayList<>();
        CountDownLatch five = new CountDownLatch(5);

        try (Closeable handle = service.follow(hostId, endpoint, id, 100, true, l -> { lines.add(l); five.countDown(); })) {
            await(five);
        }

        // stdout and stderr are separate streams: only the order within stdout is guaranteed.
        List<String> texts = lines.stream().map(LogLine::text).toList();
        assertThat(texts).containsSubsequence("old1", "old2").contains("oldErr");
        assertThat(texts.subList(texts.size() - 2, texts.size())).containsExactlyInAnyOrder("new1", "newErr");
        assertThat(lines).filteredOn(l -> l.text().equals("oldErr") || l.text().equals("newErr"))
                .allMatch(l -> l.stream() == LogLine.Stream.STDERR);
        assertThat(lines).filteredOn(l -> l.text().startsWith("old1")).allMatch(l -> l.stream() == LogLine.Stream.STDOUT);
        assertThat(lines).allMatch(l -> l.timestamp() != null);
    }

    @Test
    void tailLimitsHistory() throws Exception {
        startContainer(false, "echo a; echo b; echo c; sleep 300");
        Thread.sleep(1000);
        List<LogLine> lines = new CopyOnWriteArrayList<>();
        CountDownLatch two = new CountDownLatch(2);

        try (Closeable handle = service.follow(hostId, endpoint, id, 2, false, l -> { lines.add(l); two.countDown(); })) {
            await(two);
            Thread.sleep(300);
        }

        assertThat(lines).extracting(LogLine::text).containsExactly("b", "c");
    }

    @Test
    void ttyContainerLinesAreStdoutWithoutCarriageReturn() throws Exception {
        startContainer(true, "echo tty-line; sleep 300");
        List<LogLine> lines = new CopyOnWriteArrayList<>();
        CountDownLatch one = new CountDownLatch(1);

        try (Closeable handle = service.follow(hostId, endpoint, id, 10, false, l -> { lines.add(l); one.countDown(); })) {
            await(one);
        }

        assertThat(lines).containsExactly(new LogLine(LogLine.Stream.STDOUT, "tty-line", null));
    }

    @Test
    void closeStopsStreamAndReleasesThreads() throws Exception {
        startContainer(false, "while true; do echo tick; sleep 0.2; done");
        List<LogLine> lines = new CopyOnWriteArrayList<>();
        CountDownLatch some = new CountDownLatch(2);
        CountDownLatch ended = new CountDownLatch(1);
        // warm up the client's pools first so threads they start lazily are not counted as a leak
        try (Closeable warm = service.follow(hostId, endpoint, id, 0, false, l -> { })) {
            Thread.sleep(500);
        }
        Thread.sleep(500);
        Set<Thread> threadsBefore = Set.copyOf(Thread.getAllStackTraces().keySet());

        Closeable handle = service.follow(hostId, endpoint, id, 0, false, l -> { lines.add(l); some.countDown(); }, e -> ended.countDown());
        await(some);
        handle.close();
        handle.close(); // idempotent
        await(ended);
        int afterClose = lines.size();
        Thread.sleep(800);

        assertThat(lines).as("no lines after close").hasSize(afterClose);
        // only this follow's own docker-java stream thread counts; threads other tests start meanwhile must not fake a leak
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        List<String> leaked = newThreads(threadsBefore);
        while (!leaked.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
            leaked = newThreads(threadsBefore);
        }
        assertThat(leaked).as("stream threads released").isEmpty();
    }

    private static List<String> newThreads(Set<Thread> before) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && !before.contains(t) && t.getName().startsWith("docker-java-stream"))
                .map(Thread::getName).toList();
    }

    @Test
    void endCallbackFiresWhenContainerStops() throws Exception {
        startContainer(false, "echo bye");
        CountDownLatch ended = new CountDownLatch(1);

        try (Closeable handle = service.follow(hostId, endpoint, id, 10, false, l -> { }, e -> ended.countDown())) {
            await(ended);
        }
    }

    @Test
    void unknownContainerIsNotFound() {
        assertThatThrownBy(() -> service.follow(hostId, endpoint, "shipir-does-not-exist", 10, false, l -> { }))
                .isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }
}
