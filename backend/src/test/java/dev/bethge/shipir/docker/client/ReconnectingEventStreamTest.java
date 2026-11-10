package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.dto.DockerEventDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ReconnectingEventStreamTest {

    private static final DockerEndpoint ENDPOINT = new DockerEndpoint.Local("unix:///var/run/docker.sock");

    private final UUID hostId = UUID.randomUUID();
    private final List<Thread> threads = new ArrayList<>();
    private final StubEvents events = new StubEvents();
    private final Probe probe = new Probe();

    @AfterEach
    void cleanUp() {
        threads.forEach(Thread::interrupt);
    }

    private ReconnectingEventStream start(Duration initial, Duration max) {
        var stream = new ReconnectingEventStream(hostId, events, initial, max, probe);
        threads.add(Thread.ofVirtual().start(stream::run));
        return stream;
    }

    /** Records the requested backoff delays instead of sleeping; no wall-clock assertions needed. */
    private static final class RecordingWaiter implements ReconnectingEventStream.Waiter {
        final List<Duration> delays = new CopyOnWriteArrayList<>();
        volatile ReconnectingEventStream stream;
        volatile int stopAfter = Integer.MAX_VALUE;

        @Override
        public void await(Duration delay) {
            delays.add(delay);
            if (delays.size() >= stopAfter) {
                stream.stop();
            }
        }
    }

    private ReconnectingEventStream startRecording(RecordingWaiter waiter, Duration initial, Duration max) {
        var stream = new ReconnectingEventStream(hostId, events, initial, max, probe, waiter);
        waiter.stream = stream;
        threads.add(Thread.ofVirtual().start(stream::run));
        return stream;
    }

    @Test
    void delaysDoubleAndAreCappedAtMax() throws Exception {
        for (int i = 0; i < 5; i++) {
            events.failures.add(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        }
        var waiter = new RecordingWaiter();
        waiter.stopAfter = 5;
        startRecording(waiter, Duration.ofMillis(100), Duration.ofMillis(250));
        threads.get(0).join(5000);

        assertThat(waiter.delays).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200),
                Duration.ofMillis(250), Duration.ofMillis(250), Duration.ofMillis(250));
        assertThat(events.connectTimes).hasSize(5);
        assertThat(probe.disconnects).containsOnly("UNREACHABLE").hasSize(5);
    }

    private ReconnectingEventStream startRecording(RecordingWaiter waiter, Duration initial, Duration max,
                                                   java.util.function.LongSupplier clock) {
        var stream = new ReconnectingEventStream(hostId, events, initial, max, probe, waiter, clock);
        waiter.stream = stream;
        threads.add(Thread.ofVirtual().start(stream::run));
        return stream;
    }

    @Test
    void immediateDropsKeepGrowingTheDelayInsteadOfResetting() throws Exception {
        var waiter = new RecordingWaiter();
        waiter.stopAfter = 4;
        var clock = new java.util.concurrent.atomic.AtomicLong(); // time never advances: every connection is instant
        startRecording(waiter, Duration.ofMillis(100), Duration.ofMillis(250), clock::get);
        for (int i = 1; i <= 4; i++) {
            int n = i;
            await().atMost(10, TimeUnit.SECONDS).until(() -> events.ends.size() == n);
            events.ends.get(n - 1).accept(null); // accepted and dropped right away
        }
        threads.get(0).join(5000);

        assertThat(waiter.delays).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200),
                Duration.ofMillis(250), Duration.ofMillis(250));
    }

    @Test
    void connectionThatStayedUpLongEnoughResetsTheDelay() throws Exception {
        events.failures.add(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        events.failures.add(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        var waiter = new RecordingWaiter();
        waiter.stopAfter = 3;
        var clock = new java.util.concurrent.atomic.AtomicLong();
        startRecording(waiter, Duration.ofMillis(100), Duration.ofSeconds(5), clock::get);
        await().atMost(10, TimeUnit.SECONDS).until(() -> events.ends.size() == 1); // 3rd attempt connected
        clock.addAndGet(Duration.ofSeconds(10).toNanos()); // stayed up for the minimum
        events.ends.get(0).accept(null);
        threads.get(0).join(5000);

        assertThat(waiter.delays).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200),
                Duration.ofMillis(100));
    }

    @Test
    void connectionThatDeliveredAnEventResetsTheDelay() throws Exception {
        events.failures.add(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        events.failures.add(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        var waiter = new RecordingWaiter();
        waiter.stopAfter = 3;
        var clock = new java.util.concurrent.atomic.AtomicLong();
        startRecording(waiter, Duration.ofMillis(100), Duration.ofSeconds(5), clock::get);
        await().atMost(10, TimeUnit.SECONDS).until(() -> events.ends.size() == 1);
        events.sinks.get(0).accept(new DockerEventDto(DockerEventDto.Type.CONTAINER, "start", "c1", "web", null,
                Instant.EPOCH, null, null));
        events.ends.get(0).accept(null);
        threads.get(0).join(5000);

        assertThat(waiter.delays).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200),
                Duration.ofMillis(100));
    }

    @Test
    void throwingEndpointLookupIsAFailureAndRetried() throws Exception {
        var waiter = new RecordingWaiter();
        waiter.stopAfter = 2;
        probe.endpointFailures.set(2);
        startRecording(waiter, Duration.ofMillis(10), Duration.ofMillis(10));
        threads.get(0).join(5000);

        assertThat(threads.get(0).isAlive()).isFalse();
        assertThat(probe.disconnects).containsExactly("UNAVAILABLE", "UNAVAILABLE");
        assertThat(waiter.delays).hasSize(2);
    }

    @Test
    void throwingOnDisconnectedDoesNotKillTheWorker() throws Exception {
        events.failures.add(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        events.failures.add(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        probe.throwOnDisconnect = true;
        var waiter = new RecordingWaiter();
        waiter.stopAfter = 2;
        startRecording(waiter, Duration.ofMillis(10), Duration.ofMillis(10));
        threads.get(0).join(5000);

        // both failures were backed off and retried despite the throwing listener
        assertThat(waiter.delays).hasSize(2);
        assertThat(events.connectTimes).hasSize(2);
    }

    @Test
    void throwingOnEventDoesNotBreakTheStream() {
        probe.throwOnEvent = true;
        start(Duration.ofMillis(50), Duration.ofMillis(50));
        await().atMost(5, TimeUnit.SECONDS).until(() -> !events.sinks.isEmpty());
        var event = new DockerEventDto(DockerEventDto.Type.CONTAINER, "start", "c1", "web", null, Instant.EPOCH,
                null, null);
        events.sinks.get(0).accept(event); // must not throw into the Docker I/O thread
        assertThat(probe.received).containsExactly(event);
    }

    @Test
    void streamEndWithFailureIsReportedWithItsCode() {
        start(Duration.ofMillis(50), Duration.ofMillis(50));
        await().atMost(5, TimeUnit.SECONDS).until(() -> events.ends.size() == 1);
        events.ends.get(0).accept(new DockerConnectionException(DockerErrorCode.TIMEOUT, "slow", null));
        await().atMost(5, TimeUnit.SECONDS).until(() -> probe.disconnects.size() == 1);
        assertThat(probe.disconnects).containsExactly("TIMEOUT");
    }

    @Test
    void unexpectedRuntimeFailureIsReportedAsUnavailableAndRetried() {
        events.failures.add(new IllegalStateException("no key"));
        start(Duration.ofMillis(50), Duration.ofMillis(50));
        await().atMost(5, TimeUnit.SECONDS).until(() -> events.connectTimes.size() == 2);
        assertThat(probe.disconnects).containsExactly("UNAVAILABLE");
    }

    @Test
    void stopDuringBackoffEndsTheLoopPromptlyWithoutInterrupt() throws Exception {
        events.failures.add(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        var stream = start(Duration.ofSeconds(30), Duration.ofSeconds(30));
        await().atMost(5, TimeUnit.SECONDS).until(() -> probe.disconnects.size() == 1);

        stream.stop();

        Thread worker = threads.get(0);
        worker.join(2000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(events.connectTimes).hasSize(1);
    }

    @Test
    void stopClosesTheOpenStreamAndEndsTheLoop() throws Exception {
        var stream = start(Duration.ofMillis(50), Duration.ofMillis(50));
        await().atMost(5, TimeUnit.SECONDS).until(() -> probe.connected.get() == 1);

        stream.stop();

        threads.get(0).join(2000);
        assertThat(threads.get(0).isAlive()).isFalse();
        assertThat(events.closed.get()).isEqualTo(1);
        assertThat(probe.disconnects).isEmpty();
        assertThat(stream.isStopped()).isTrue();
    }

    @Test
    void goneHostEndsTheLoopWithoutRetry() throws Exception {
        probe.endpoint = Optional.empty();
        start(Duration.ofMillis(50), Duration.ofMillis(50));
        threads.get(0).join(2000);
        assertThat(threads.get(0).isAlive()).isFalse();
        assertThat(probe.hostGone.get()).isEqualTo(1);
        assertThat(events.connectTimes).isEmpty();
    }

    @Test
    void interruptEndsTheLoopAndClosesTheStream() throws Exception {
        start(Duration.ofMillis(50), Duration.ofMillis(50));
        await().atMost(5, TimeUnit.SECONDS).until(() -> probe.connected.get() == 1);
        threads.get(0).interrupt();
        threads.get(0).join(2000);
        assertThat(threads.get(0).isAlive()).isFalse();
        assertThat(events.closed.get()).isEqualTo(1);
    }

    @Test
    void eventsAreForwardedToTheListener() {
        start(Duration.ofMillis(50), Duration.ofMillis(50));
        await().atMost(5, TimeUnit.SECONDS).until(() -> !events.sinks.isEmpty());
        var event = new DockerEventDto(DockerEventDto.Type.CONTAINER, "start", "c1", "web", null, Instant.EPOCH,
                null, null);
        events.sinks.get(0).accept(event);
        assertThat(probe.received).containsExactly(event);
    }

    private static final class Probe implements ReconnectingEventStream.Listener {
        volatile Optional<DockerEndpoint> endpoint = Optional.of(ENDPOINT);
        final AtomicInteger endpointFailures = new AtomicInteger();
        volatile boolean throwOnDisconnect;
        volatile boolean throwOnEvent;
        final List<String> disconnects = new CopyOnWriteArrayList<>();
        final List<DockerEventDto> received = new CopyOnWriteArrayList<>();
        final AtomicInteger connected = new AtomicInteger();
        final AtomicInteger hostGone = new AtomicInteger();

        @Override
        public Optional<DockerEndpoint> endpoint() {
            if (endpointFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("lookup failed");
            }
            return endpoint;
        }

        @Override
        public void onEvent(DockerEventDto event) {
            received.add(event);
            if (throwOnEvent) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public void onConnected() {
            connected.incrementAndGet();
        }

        @Override
        public void onDisconnected(String code) {
            disconnects.add(code);
            if (throwOnDisconnect) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public void onHostGone() {
            hostGone.incrementAndGet();
        }
    }

    private static final class StubEvents implements DockerEventService {
        final List<RuntimeException> failures = new CopyOnWriteArrayList<>();
        final List<Instant> connectTimes = new CopyOnWriteArrayList<>();
        final List<Consumer<DockerEventDto>> sinks = new CopyOnWriteArrayList<>();
        final List<Consumer<Throwable>> ends = new CopyOnWriteArrayList<>();
        final AtomicInteger closed = new AtomicInteger();

        @Override
        public Closeable stream(UUID hostId, DockerEndpoint endpoint, Consumer<DockerEventDto> onEvent) {
            return stream(hostId, endpoint, onEvent, e -> {
            });
        }

        @Override
        public Closeable stream(UUID hostId, DockerEndpoint endpoint, Consumer<DockerEventDto> onEvent,
                                Consumer<Throwable> onEnd) {
            connectTimes.add(Instant.now());
            if (!failures.isEmpty()) {
                throw failures.remove(0);
            }
            sinks.add(onEvent);
            ends.add(onEnd);
            return closed::incrementAndGet;
        }
    }
}
