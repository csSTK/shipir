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
    private final StreamViewers viewers = mock(StreamViewers.class);
    private final StreamAccess access = mock(StreamAccess.class);
    private DockerEventStreamService service;

    @BeforeEach
    void setUp() {
        when(hosts.findById(hostId)).thenReturn(Optional.of(DockerHost.local("h", UUID.randomUUID())));
        when(resolver.endpointOf(any())).thenReturn(new DockerEndpoint.Local("unix:///x"));
        when(viewers.isAdmin(any())).thenReturn(true);
        when(viewers.isAdmin("viewer")).thenReturn(false);
        service = new DockerEventStreamService(stub, hosts, resolver, sender, new DockerEventProperties(
                Duration.ofMillis(100), 3, Duration.ofMillis(100), Duration.ofMillis(400)), viewers, access);
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
        service.subscribe(hostId, "u", "s1", "a");
        service.subscribe(hostId, "u", "s2", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> stub.sinks.size() == 1);
        assertThat(service.subscriberCount(hostId)).isEqualTo(2);

        service.unsubscribe("s1", null);
        assertThat(stub.closed.get()).isZero();
        service.unsubscribe("s2", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> stub.closed.get() == 1);
        assertThat(service.activeStreams()).isZero();
        assertThat(stub.sinks).hasSize(1);

        service.subscribe(hostId, "u", "s3", "c");
        await().atMost(5, TimeUnit.SECONDS).until(() -> stub.sinks.size() == 2);
    }

    @Test
    void revalidateDropsOnlyTheSubscriptionsOfTheUserWhoLostAccess() {
        service.subscribe(hostId, "alice", "s1", "a");
        service.subscribe(hostId, "bob", "s2", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> stub.sinks.size() == 1);

        service.revalidate("alice", host -> false);
        assertThat(service.subscriberCount(hostId)).isEqualTo(1);
        service.revalidate("bob", host -> true);
        assertThat(service.subscriberCount(hostId)).isEqualTo(1);

        service.revalidate("bob", host -> false);
        assertThat(service.subscriberCount(hostId)).isZero();
        await().atMost(5, TimeUnit.SECONDS).until(() -> stub.closed.get() == 1);
    }

    @Test
    void revalidateAsksOnlyForHostsTheUserIsSubscribedTo() {
        UUID otherHost = UUID.randomUUID();
        when(hosts.findById(otherHost)).thenReturn(Optional.of(DockerHost.local("o", UUID.randomUUID())));
        service.subscribe(hostId, "alice", "s1", "a");
        service.subscribe(otherHost, "bob", "s2", "b");
        List<UUID> asked = new CopyOnWriteArrayList<>();

        service.revalidate("alice", host -> {
            asked.add(host);
            return false;
        });

        assertThat(asked).containsExactly(hostId);
        assertThat(service.subscriberCount(hostId)).isZero();
        assertThat(service.subscriberCount(otherHost)).isEqualTo(1);
    }

    @Test
    void throwingPredicateDropsThatHostSubscriptionAndStillChecksTheOthers() {
        UUID other = UUID.randomUUID();
        when(hosts.findById(other)).thenReturn(Optional.of(DockerHost.local("o", UUID.randomUUID())));
        service.subscribe(hostId, "alice", "s1", "a");
        service.subscribe(other, "alice", "s1", "b");

        service.revalidate("alice", host -> {
            if (host.equals(hostId)) {
                throw new IllegalStateException("db down");
            }
            return true;
        });

        assertThat(service.subscriberCount(hostId)).isZero();
        assertThat(service.subscriberCount(other)).isEqualTo(1);
    }

    @Test
    void revalidateKeepsTheSubscriptionWhenAccessIsStillGranted() {
        service.subscribe(hostId, "alice", "s1", "a");
        service.revalidate("alice", host -> true);
        assertThat(service.subscriberCount(hostId)).isEqualTo(1);
    }

    @Test
    void dropHostEndsEverySubscriptionOfThatHostAndClosesTheStream() {
        service.subscribe(hostId, "alice", "s1", "a");
        service.subscribe(hostId, "bob", "s2", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> stub.sinks.size() == 1);

        service.dropHost(java.util.UUID.randomUUID());
        assertThat(service.subscriberCount(hostId)).isEqualTo(2);

        service.dropHost(hostId);
        assertThat(service.subscriberCount(hostId)).isZero();
        await().atMost(5, TimeUnit.SECONDS).until(() -> stub.closed.get() == 1);
    }

    @Test
    void connectedStatusThenDebouncedBatchOfDistinctEvents() {
        service.subscribe(hostId, "u", "s1", "a");
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
        service.subscribe(hostId, "u", "s1", "a");
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
        service.subscribe(hostId, "u", "s1", "a");
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
        service.subscribe(hostId, "u", "s1", "a");
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
        service.subscribe(hostId, "u", "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> frames("s1").size() == 1);
        service.subscribe(hostId, "u", "s2", "b");
        assertThat(frames("s2")).extracting(DockerEventMessage::status)
                .containsExactly(DockerEventMessage.Status.CONNECTED);
        assertThat(stub.sinks).hasSize(1);
    }

    @Test
    void unknownHostSendsNotFoundAndDoesNotRetry() {
        UUID ghost = UUID.randomUUID();
        when(hosts.findById(ghost)).thenReturn(Optional.empty());
        service.subscribe(ghost, "u", "s1", "a");
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(sender).send(eq("s1"), any(),
                eq(DockerEventStreamService.destination(ghost)), payload.capture()));
        assertThat(((DockerEventMessage) payload.getValue()).code()).isEqualTo("NOT_FOUND");
        assertThat(stub.connectTimes).isEmpty();
        assertThat(service.activeStreams()).isZero();
    }

    @Test
    void noFramesAfterLastUnsubscribe() throws Exception {
        service.subscribe(hostId, "u", "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !stub.sinks.isEmpty());
        var sink = stub.lastSink();
        sink.accept(event("start", "c1"));
        service.unsubscribe("s1", "a");
        Thread.sleep(300);
        assertThat(frames("s1").stream().filter(f -> f.type() == DockerEventMessage.Type.EVENT)).isEmpty();
    }

    /** Records the worker thread at the host lookup and whether the lookup was interrupted. */
    private final class LookupProbe {
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);
        volatile Thread worker;
        volatile boolean interruptedInLookup;

        void install(boolean block) {
            when(hosts.findById(hostId)).thenAnswer(inv -> {
                worker = Thread.currentThread();
                entered.countDown();
                if (block) {
                    proceed.await();
                }
                interruptedInLookup = Thread.currentThread().isInterrupted();
                return Optional.of(DockerHost.local("h", UUID.randomUUID()));
            });
        }
    }

    /**
     * Unsubscribes while holding the stream's monitor and the reconnect loop's private lock (same order as
     * production: stream monitor first, then the loop lock inside {@code stop()}). A worker woken by the stop
     * has to re-acquire that loop lock to see {@code stopped} and return, so it stays alive (blocked) until
     * this method returns; this makes the read deterministic. Returns whether the worker was interrupted at
     * that point (a dead thread always reports false).
     */
    private boolean unsubscribeAndReadInterruptWhileAlive(Thread worker, String session, String subscription)
            throws Exception {
        var field = DockerEventStreamService.class.getDeclaredField("streams");
        field.setAccessible(true);
        Object stream;
        synchronized (field.get(service)) {
            stream = ((java.util.Map<?, ?>) field.get(service)).get(hostId);
        }
        var loopField = stream.getClass().getDeclaredField("loop");
        loopField.setAccessible(true);
        Object loop = loopField.get(stream);
        var lockField = loop.getClass().getDeclaredField("lock");
        lockField.setAccessible(true);
        Object loopLock = lockField.get(loop);
        synchronized (stream) {
            synchronized (loopLock) {
                service.unsubscribe(session, subscription);
                assertThat(worker.isAlive()).isTrue();
                return worker.isInterrupted();
            }
        }
    }

    @Test
    void stopWhileWaitingOnStreamEndsWorkerWithoutInterrupt() throws Exception {
        var probe = new LookupProbe();
        probe.install(false);
        service.subscribe(hostId, "u", "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !stub.sinks.isEmpty());

        boolean interrupted = unsubscribeAndReadInterruptWhileAlive(probe.worker, "s1", "a");

        assertThat(interrupted).isFalse();
        await().atMost(5, TimeUnit.SECONDS).until(() -> !probe.worker.isAlive());
    }

    @Test
    void stopDuringHostLookupLetsLookupFinishAndOpensNoStream() {
        var probe = new LookupProbe();
        probe.install(true);
        service.subscribe(hostId, "u", "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> probe.entered.getCount() == 0);

        service.unsubscribe("s1", "a");
        // the worker is still blocked inside the lookup here, so its flag is meaningful
        assertThat(probe.worker.isAlive()).isTrue();
        assertThat(probe.worker.isInterrupted()).isFalse();
        probe.proceed.countDown();

        await().atMost(5, TimeUnit.SECONDS).until(() -> !probe.worker.isAlive());
        assertThat(probe.interruptedInLookup).isFalse();
        assertThat(stub.connectTimes).isEmpty();
    }

    @Test
    void stopDuringBackoffEndsWorkerPromptlyWithoutInterrupt() throws Exception {
        service.shutdown();
        service = new DockerEventStreamService(stub, hosts, resolver, sender, new DockerEventProperties(
                Duration.ofMillis(100), 3, Duration.ofSeconds(30), Duration.ofSeconds(30)), viewers, access);
        var probe = new LookupProbe();
        probe.install(false);
        stub.failures.add(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null));
        service.subscribe(hostId, "u", "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> stub.connectTimes.size() == 1);
        await().atMost(5, TimeUnit.SECONDS).until(() -> frames("s1").size() == 1); // DISCONNECTED, now sleeping

        boolean interrupted = unsubscribeAndReadInterruptWhileAlive(probe.worker, "s1", "a");

        assertThat(interrupted).isFalse();
        await().atMost(2, TimeUnit.SECONDS).until(() -> !probe.worker.isAlive());
    }

    @Test
    void parsesOnlyEventTopics() {
        assertThat(DockerEventStreamService.parse("/topic/hosts/" + hostId + "/events")).isEqualTo(hostId);
        assertThat(DockerEventStreamService.parse("/topic/hosts/" + hostId + "/events/x")).isNull();
        assertThat(DockerEventStreamService.parse("/topic/hosts/not-a-uuid/events")).isNull();
        assertThat(DockerEventStreamService.parse("/topic/hosts/" + hostId + "/containers/a/logs")).isNull();
    }

    // --- shipr-kqbc.46: STATUS codes are chosen per subscriber, replay included ---

    private List<DockerEventMessage> statusFrames(String session) {
        return frames(session).stream().filter(m -> m.type() == DockerEventMessage.Type.STATUS).toList();
    }

    @Test
    void disconnectedCodeIsCollapsedForViewersAndRawForAdmins() {
        stub.failures.add(new DockerConnectionException(DockerErrorCode.TLS_ERROR, "bad cert", null));
        service.subscribe(hostId, "viewer", "sv", "a");
        service.subscribe(hostId, "root", "sa", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !statusFrames("sv").isEmpty() && !statusFrames("sa").isEmpty());

        // a later CONNECTED status (null code) after the stub's retry is legitimate; only error codes matter here
        assertThat(statusFrames("sv")).extracting(DockerEventMessage::code).filteredOn(c -> c != null)
                .containsOnly("UNREACHABLE");
        assertThat(statusFrames("sa")).extracting(DockerEventMessage::code).contains("TLS_ERROR");
    }

    @Test
    void replayedStatusToALateViewerIsCollapsedToo() {
        service.shutdown();
        service = new DockerEventStreamService(stub, hosts, resolver, sender, new DockerEventProperties(
                Duration.ofMillis(100), 3, Duration.ofSeconds(30), Duration.ofSeconds(30)), viewers, access);
        stub.failures.add(new DockerConnectionException(DockerErrorCode.PERMISSION_DENIED, "no", null));
        service.subscribe(hostId, "root", "sa", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !statusFrames("sa").isEmpty());

        service.subscribe(hostId, "viewer", "sv", "a"); // the shared latest STATUS is replayed to it

        assertThat(statusFrames("sv")).isNotEmpty();
        // a later CONNECTED status (null code) after the stub's retry is legitimate; only error codes matter here
        assertThat(statusFrames("sv")).extracting(DockerEventMessage::code).filteredOn(c -> c != null)
                .containsOnly("UNREACHABLE");
    }

    // --- shipr-kqbc.48: events of system-managed projects go to ADMINs only ---

    @Test
    void eventsOfSystemManagedProjectsAreWithheldFromViewers() {
        when(access.systemManagedProjects(hostId)).thenReturn(java.util.Set.of("shipir-proxy"));
        service.subscribe(hostId, "viewer", "sv", "a");
        service.subscribe(hostId, "root", "sa", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !stub.sinks.isEmpty());
        stub.lastSink().accept(new DockerEventDto(DockerEventDto.Type.CONTAINER, "die", "p1", "proxy",
                "shipir-proxy", Instant.now()));
        stub.lastSink().accept(new DockerEventDto(DockerEventDto.Type.CONTAINER, "start", "w1", "web",
                "shop", Instant.now()));
        await().atMost(5, TimeUnit.SECONDS).until(() -> eventsOf("sa").size() == 2);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !eventsOf("sv").isEmpty());

        assertThat(eventsOf("sv")).extracting(DockerEventDto::id).containsExactly("w1");
        assertThat(eventsOf("sa")).extracting(DockerEventDto::id).containsExactlyInAnyOrder("p1", "w1");
    }

    @Test
    void aBatchOfOnlySystemManagedEventsSendsNothingToViewers() throws Exception {
        when(access.systemManagedProjects(hostId)).thenReturn(java.util.Set.of("shipir-proxy"));
        service.subscribe(hostId, "viewer", "sv", "a");
        service.subscribe(hostId, "root", "sa", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !stub.sinks.isEmpty());
        stub.lastSink().accept(new DockerEventDto(DockerEventDto.Type.CONTAINER, "die", "p1", "proxy",
                "shipir-proxy", Instant.now()));
        await().atMost(5, TimeUnit.SECONDS).until(() -> eventsOf("sa").size() == 1);

        assertThat(eventsOf("sv")).isEmpty();
    }

    @Test
    void failingSystemProjectLookupHidesProjectLabelledEventsFromViewers() {
        when(access.systemManagedProjects(hostId)).thenThrow(new IllegalStateException("db down"));
        service.subscribe(hostId, "viewer", "sv", "a");
        service.subscribe(hostId, "root", "sa", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !stub.sinks.isEmpty());
        stub.lastSink().accept(new DockerEventDto(DockerEventDto.Type.CONTAINER, "die", "p1", "proxy",
                "shipir-proxy", Instant.now()));
        stub.lastSink().accept(new DockerEventDto(DockerEventDto.Type.IMAGE, "pull", "img", "img", null,
                Instant.now()));
        await().atMost(5, TimeUnit.SECONDS).until(() -> eventsOf("sa").size() == 2);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !eventsOf("sv").isEmpty());

        assertThat(eventsOf("sv")).extracting(DockerEventDto::id).containsExactly("img");
    }

    private List<DockerEventDto> eventsOf(String session) {
        return frames(session).stream().filter(m -> m.type() == DockerEventMessage.Type.EVENT)
                .flatMap(m -> m.events().stream()).toList();
    }

    // --- shipr-kqbc.45: a throwing unsubscribe does not skip the remaining items ---

    @Test
    void throwingUnsubscribeDuringRevalidateDoesNotSkipTheUsersOtherHosts() {
        UUID other = UUID.randomUUID();
        when(hosts.findById(other)).thenReturn(Optional.of(DockerHost.local("o", UUID.randomUUID())));
        var spy = org.mockito.Mockito.spy(service);
        spy.subscribe(hostId, "alice", "s1", "a");
        spy.subscribe(other, "alice", "s1", "b");
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(spy).unsubscribe("s1", "a");

        spy.revalidate("alice", host -> false);

        assertThat(spy.subscriberCount(other)).isZero();
    }

    @Test
    void throwingUnsubscribeDuringDropHostDoesNotSkipTheOtherSubscribers() {
        var spy = org.mockito.Mockito.spy(service);
        spy.subscribe(hostId, "alice", "s1", "a");
        spy.subscribe(hostId, "bob", "s2", "b");
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(spy).unsubscribe("s1", "a");

        spy.dropHost(hostId);

        assertThat(spy.subscriberCount(hostId)).isEqualTo(1); // bob dropped although alice's unsubscribe threw
    }

    // --- demotion: the admin flag is not frozen at SUBSCRIBE ---

    @Test
    void demotedAdminNoLongerGetsRawCodesOrSystemEvents() {
        when(access.systemManagedProjects(hostId)).thenReturn(java.util.Set.of("shipir-proxy"));
        service.subscribe(hostId, "root", "sa", "b"); // admin when subscribing
        await().atMost(5, TimeUnit.SECONDS).until(() -> !stub.sinks.isEmpty());

        service.refreshAdmin("root", false); // role changed to VIEWER, still sees the whole host

        stub.lastSink().accept(new DockerEventDto(DockerEventDto.Type.CONTAINER, "die", "p1", "proxy",
                "shipir-proxy", Instant.now()));
        stub.lastSink().accept(new DockerEventDto(DockerEventDto.Type.CONTAINER, "start", "w1", "web",
                "shop", Instant.now()));
        await().atMost(5, TimeUnit.SECONDS).until(() -> !eventsOf("sa").isEmpty());
        assertThat(eventsOf("sa")).extracting(DockerEventDto::id).containsExactly("w1");
        stub.ends.get(stub.ends.size() - 1).accept(new DockerConnectionException(DockerErrorCode.TLS_ERROR, "x", null));
        await().atMost(5, TimeUnit.SECONDS).until(() -> statusFrames("sa").stream()
                .anyMatch(m -> m.status() == DockerEventMessage.Status.DISCONNECTED));
        assertThat(statusFrames("sa")).extracting(DockerEventMessage::code).doesNotContain("TLS_ERROR");
    }

    @Test
    void refreshAdminOnlyTouchesTheNamedUser() {
        service.subscribe(hostId, "root", "sa", "b");
        service.subscribe(hostId, "root2", "sb", "c"); // viewers.isAdmin default true: both are admins
        await().atMost(5, TimeUnit.SECONDS).until(() -> !stub.sinks.isEmpty());

        service.refreshAdmin("root", false);
        stub.ends.get(stub.ends.size() - 1).accept(new DockerConnectionException(DockerErrorCode.TLS_ERROR, "x", null));
        await().atMost(5, TimeUnit.SECONDS).until(() -> disconnectedCodes("sa").size() >= 1
                && disconnectedCodes("sb").size() >= 1);

        assertThat(disconnectedCodes("sa")).doesNotContain("TLS_ERROR"); // demoted: collapsed
        assertThat(disconnectedCodes("sb")).contains("TLS_ERROR"); // untouched admin keeps the raw code
    }

    private List<String> disconnectedCodes(String session) {
        return statusFrames(session).stream().filter(m -> m.status() == DockerEventMessage.Status.DISCONNECTED)
                .map(DockerEventMessage::code).toList();
    }
}
