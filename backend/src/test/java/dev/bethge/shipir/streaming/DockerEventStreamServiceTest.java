package dev.bethge.shipir.streaming;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.DockerEventService;
import dev.bethge.shipir.docker.dto.DockerEventDto;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit test with a scripted {@link DockerEventService} and a recording frame sender (no Spring, no Docker). */
class DockerEventStreamServiceTest {

    /** Scripted daemon: each connect either throws the next queued failure or opens a stream. */
    static final class StubEvents implements DockerEventService {
        final List<DockerConnectionException> failures = new CopyOnWriteArrayList<>();
        final List<Consumer<DockerEventDto>> sinks = new CopyOnWriteArrayList<>();
        final List<Consumer<Throwable>> ends = new CopyOnWriteArrayList<>();
        final List<Instant> connectTimes = new CopyOnWriteArrayList<>();
        final AtomicInteger closed = new AtomicInteger();

        @Override
        public Closeable stream(UUID hostId, DockerEndpoint endpoint, Consumer<DockerEventDto> onEvent) {
            return stream(hostId, endpoint, onEvent, e -> { });
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

        Consumer<DockerEventDto> lastSink() {
            return sinks.get(sinks.size() - 1);
        }
    }

    private final UUID hostId = UUID.randomUUID();
    private final StubEvents stub = new StubEvents();
    private final StompFrameSender sender = mock(StompFrameSender.class);
    private final DockerHostRepository hosts = mock(DockerHostRepository.class);
    private final HostEndpointResolver resolver = mock(HostEndpointResolver.class);
    private DockerEventStreamService service;

    @BeforeEach
    void setUp() {
        when(hosts.findById(hostId)).thenReturn(Optional.of(DockerHost.local("h", UUID.randomUUID())));
        when(resolver.endpointOf(any())).thenReturn(new DockerEndpoint.Local("unix:///x"));
        service = new DockerEventStreamService(stub, hosts, resolver, sender, new DockerEventProperties(
                Duration.ofMillis(100), 3, Duration.ofMillis(100), Duration.ofMillis(400)));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private static DockerEventDto event(String action, String id) {
        return new DockerEventDto(DockerEventDto.Type.CONTAINER, action, id, "n", null, Instant.now());
    }

    private List<DockerEventMessage> frames(String session) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sender, org.mockito.Mockito.atLeast(0)).send(eq(session), any(), eq(DockerEventStreamService.destination(hostId)),
                payload.capture());
        return payload.getAllValues().stream().map(DockerEventMessage.class::cast).toList();
    }

    @Test
    void firstSubscriberStartsOneStreamAndLastStopsIt() {
        service.subscribe(hostId, "s1", "a");
        service.subscribe(hostId, "s2", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> stub.sinks.size() == 1);
        assertThat(service.subscriberCount(hostId)).isEqualTo(2);

        service.unsubscribe("s1", null);
        assertThat(stub.closed.get()).isZero();
        service.unsubscribe("s2", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> stub.closed.get() == 1);
        assertThat(service.activeStreams()).isZero();
        assertThat(stub.sinks).hasSize(1);

        service.subscribe(hostId, "s3", "c");
        await().atMost(5, TimeUnit.SECONDS).until(() -> stub.sinks.size() == 2);
    }

    @Test
    void connectedStatusThenDebouncedBatchOfDistinctEvents() {
        service.subscribe(hostId, "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !stub.sinks.isEmpty());
        stub.lastSink().accept(event("start", "c1"));
        stub.lastSink().accept(event("start", "c1")); // duplicate within the window
        stub.lastSink().accept(event("die", "c1"));
        await().atMost(5, TimeUnit.SECONDS).until(() -> frames("s1").size() == 2);

        List<DockerEventMessage> got = frames("s1");
        assertThat(got.get(0).type()).isEqualTo(DockerEventMessage.Type.STATUS);
        assertThat(got.get(0).status()).isEqualTo(DockerEventMessage.Status.CONNECTED);
        assertThat(got.get(1).type()).isEqualTo(DockerEventMessage.Type.EVENT);
        assertThat(got.get(1).events()).extracting(DockerEventDto::action).containsExactly("start", "die");
        assertThat(got.get(1).omitted()).isNull();
        assertThat(got.get(1).seq()).isEqualTo(got.get(0).seq() + 1);
    }

    @Test
    void batchBeyondCapIsCountedNotSent() {
        service.subscribe(hostId, "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !stub.sinks.isEmpty());
        for (int i = 0; i < 5; i++) stub.lastSink().accept(event("start", "c" + i));
        await().atMost(5, TimeUnit.SECONDS).until(() -> frames("s1").size() == 2);
        DockerEventMessage batch = frames("s1").get(1);
        assertThat(batch.events()).hasSize(3);
        assertThat(batch.omitted()).isEqualTo(2);
    }

    @Test
    void hostDownSendsOneDisconnectedFrameAndReconnectsWithBackoff() {
        for (int i = 0; i < 3; i++) {
            stub.failures.add(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        }
        service.subscribe(hostId, "s1", "a");
        await().atMost(10, TimeUnit.SECONDS).until(() -> !stub.sinks.isEmpty());

        List<DockerEventMessage> got = frames("s1");
        assertThat(got).extracting(DockerEventMessage::status).containsExactly(
                DockerEventMessage.Status.DISCONNECTED, DockerEventMessage.Status.CONNECTED);
        assertThat(got.get(0).code()).isEqualTo("UNREACHABLE");
        // three failed attempts, waits 100ms, 200ms, 400ms (capped) between the four attempts
        assertThat(stub.connectTimes).hasSize(4);
        long first = Duration.between(stub.connectTimes.get(0), stub.connectTimes.get(1)).toMillis();
        long second = Duration.between(stub.connectTimes.get(1), stub.connectTimes.get(2)).toMillis();
        long third = Duration.between(stub.connectTimes.get(2), stub.connectTimes.get(3)).toMillis();
        assertThat(first).isGreaterThanOrEqualTo(90);
        assertThat(second).isGreaterThanOrEqualTo(190);
        assertThat(third).isGreaterThanOrEqualTo(390);
    }

    @Test
    void droppedStreamIsReportedAndReopened() {
        service.subscribe(hostId, "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !stub.ends.isEmpty());
        stub.ends.get(0).accept(null); // daemon closed the connection
        await().atMost(5, TimeUnit.SECONDS).until(() -> stub.sinks.size() == 2);
        await().atMost(5, TimeUnit.SECONDS).until(() -> frames("s1").size() == 3);
        assertThat(frames("s1")).extracting(DockerEventMessage::status).containsExactly(
                DockerEventMessage.Status.CONNECTED, DockerEventMessage.Status.DISCONNECTED,
                DockerEventMessage.Status.CONNECTED);
    }

    @Test
    void lateSubscriberGetsLatestStatusFrame() {
        service.subscribe(hostId, "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> frames("s1").size() == 1);
        service.subscribe(hostId, "s2", "b");
        assertThat(frames("s2")).extracting(DockerEventMessage::status)
                .containsExactly(DockerEventMessage.Status.CONNECTED);
        assertThat(stub.sinks).hasSize(1);
    }

    @Test
    void unknownHostSendsNotFoundAndDoesNotRetry() {
        UUID ghost = UUID.randomUUID();
        when(hosts.findById(ghost)).thenReturn(Optional.empty());
        service.subscribe(ghost, "s1", "a");
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(sender).send(eq("s1"), any(),
                eq(DockerEventStreamService.destination(ghost)), payload.capture()));
        assertThat(((DockerEventMessage) payload.getValue()).code()).isEqualTo("NOT_FOUND");
        assertThat(stub.connectTimes).isEmpty();
        assertThat(service.activeStreams()).isZero();
    }

    @Test
    void noFramesAfterLastUnsubscribe() throws Exception {
        service.subscribe(hostId, "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !stub.sinks.isEmpty());
        var sink = stub.lastSink();
        sink.accept(event("start", "c1"));
        service.unsubscribe("s1", "a");
        Thread.sleep(300);
        assertThat(frames("s1").stream().filter(f -> f.type() == DockerEventMessage.Type.EVENT)).isEmpty();
    }

    @Test
    void parsesOnlyEventTopics() {
        assertThat(DockerEventStreamService.parse("/topic/hosts/" + hostId + "/events")).isEqualTo(hostId);
        assertThat(DockerEventStreamService.parse("/topic/hosts/" + hostId + "/events/x")).isNull();
        assertThat(DockerEventStreamService.parse("/topic/hosts/not-a-uuid/events")).isNull();
        assertThat(DockerEventStreamService.parse("/topic/hosts/" + hostId + "/containers/a/logs")).isNull();
    }
}
