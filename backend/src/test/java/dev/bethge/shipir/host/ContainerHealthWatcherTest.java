package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.DockerEventService;
import dev.bethge.shipir.docker.dto.DockerEventDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit test with stubbed Docker events: classification, dedupe window, deployment lookup, per-host workers. */
class ContainerHealthWatcherTest {

    private static final Instant T0 = Instant.parse("2026-10-24T10:00:00Z");

    private final UUID hostId = UUID.randomUUID();
    private final UUID deploymentId = UUID.randomUUID();
    private final DockerHostRepository hosts = mock(DockerHostRepository.class);
    private final HostEndpointResolver endpoints = mock(HostEndpointResolver.class);
    private final ManagedDeploymentLookup managed = mock(ManagedDeploymentLookup.class);
    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final MutableClock clock = new MutableClock(T0);
    private DockerHost host;
    private ContainerHealthWatcher watcher;
    private FakeEvents fake;

    @BeforeEach
    void setUp() {
        host = mock(DockerHost.class);
        when(host.getId()).thenReturn(hostId);
        when(host.getName()).thenReturn("prod");
        when(hosts.findById(hostId)).thenReturn(Optional.of(host));
        when(hosts.findAll()).thenReturn(List.of(host));
        when(endpoints.endpointOf(any())).thenReturn(new DockerEndpoint.Local("/var/run/docker.sock"));
        when(managed.managedDeploymentIds(hostId)).thenReturn(Map.of("shop", deploymentId));
        fake = new FakeEvents();
        watcher = new ContainerHealthWatcher(hosts, endpoints, fake, managed, published::add, clock,
                new ContainerWatchProperties(true, Duration.ofMinutes(1), Duration.ofMinutes(10),
                        Duration.ofMillis(10), Duration.ofMillis(50)));
    }

    private static DockerEventDto container(String action, String id, String project, Integer exit) {
        return new DockerEventDto(DockerEventDto.Type.CONTAINER, action, id, "web-1", project, T0, exit);
    }

    @Test
    void nonZeroExitIsReportedAsCrashWithContainerAndHost() {
        watcher.handle(hostId, container("die", "c1", null, 1));
        assertThat(published).singleElement().isInstanceOfSatisfying(ContainerProblemEvent.class, e -> {
            assertThat(e.kind()).isEqualTo(ContainerProblemEvent.Kind.CRASHED);
            assertThat(e.hostId()).isEqualTo(hostId);
            assertThat(e.hostName()).isEqualTo("prod");
            assertThat(e.containerId()).isEqualTo("c1");
            assertThat(e.containerName()).isEqualTo("web-1");
            assertThat(e.exitCode()).isEqualTo(1);
            assertThat(e.deploymentId()).isNull();
            assertThat(e.occurredAt()).isEqualTo(T0);
        });
    }

    @Test
    void cleanExitSigtermAndOtherEventsAreIgnored() {
        watcher.handle(hostId, container("die", "c1", null, 0));
        watcher.handle(hostId, container("die", "c1", null, ContainerHealthWatcher.SIGTERM_EXIT));
        watcher.handle(hostId, container("die", "c1", null, null));
        watcher.handle(hostId, container("start", "c1", null, null));
        watcher.handle(hostId, container("health_status: healthy", "c1", null, null));
        watcher.handle(hostId, new DockerEventDto(DockerEventDto.Type.IMAGE, "die", "i", null, null, T0, 1));
        assertThat(published).isEmpty();
    }

    @Test
    void dieAfterKillEventOfSameContainerIsNotReported() {
        watcher.handle(hostId, container("kill", "c1", null, null));
        watcher.handle(hostId, container("die", "c1", null, 137));
        assertThat(published).isEmpty();
    }

    @Test
    void killOfOtherContainerOrExpiredKillDoesNotSuppressCrash() {
        watcher.handle(hostId, container("kill", "c2", null, null));
        watcher.handle(hostId, container("die", "c1", null, 137));
        assertThat(published).hasSize(1);

        watcher.handle(hostId, container("kill", "c3", null, null));
        clock.advance(Duration.ofMinutes(6));
        watcher.handle(hostId, container("die", "c3", null, 137));
        assertThat(published).hasSize(2);
    }

    @Test
    void killSuppressesOnlyTheNextDie() {
        watcher.handle(hostId, container("kill", "c1", null, null));
        watcher.handle(hostId, container("die", "c1", null, 137));
        clock.advance(Duration.ofMinutes(11)); // past the dedupe window
        watcher.handle(hostId, container("die", "c1", null, 137)); // later crash without a kill
        assertThat(published).hasSize(1);
    }

    @Test
    void unhealthyIsReported() {
        watcher.handle(hostId, container("health_status: unhealthy", "c1", null, null));
        assertThat(published).singleElement().isInstanceOfSatisfying(ContainerProblemEvent.class, e -> {
            assertThat(e.kind()).isEqualTo(ContainerProblemEvent.Kind.UNHEALTHY);
            assertThat(e.exitCode()).isNull();
        });
    }

    @Test
    void repeatedCrashWithinWindowIsReportedOnceAndAgainAfterIt() {
        watcher.handle(hostId, container("die", "c1", null, 1));
        clock.advance(Duration.ofMinutes(5));
        watcher.handle(hostId, container("die", "c1", null, 1));
        assertThat(published).hasSize(1);

        watcher.handle(hostId, container("die", "c2", null, 1)); // other container
        watcher.handle(hostId, container("health_status: unhealthy", "c1", null, null)); // other kind
        assertThat(published).hasSize(3);

        clock.advance(Duration.ofMinutes(6));
        watcher.handle(hostId, container("die", "c1", null, 1));
        assertThat(published).hasSize(4);
    }

    @Test
    void containerOfManagedProjectCarriesDeployment() {
        watcher.handle(hostId, container("die", "c1", "shop", 2));
        watcher.handle(hostId, container("die", "c2", "unmanaged", 2));
        assertThat(published).hasSize(2);
        assertThat(((ContainerProblemEvent) published.get(0)).deploymentId()).isEqualTo(deploymentId);
        assertThat(((ContainerProblemEvent) published.get(0)).deploymentName()).isEqualTo("shop");
        assertThat(((ContainerProblemEvent) published.get(1)).deploymentId()).isNull();
    }

    @Test
    void syncStartsOneStreamPerHostAndForwardsEvents() {
        watcher.sync();
        watcher.sync();
        await().atMost(5, SECONDS).until(() -> fake.opened.size() == 1);
        fake.opened.get(0).accept(container("die", "c9", null, 3));
        assertThat(published).hasSize(1);
        watcher.shutdown();
    }

    @Test
    void droppedStreamIsReopenedAndUnreachableHostIsRetried() {
        fake.failFirst = 1;
        watcher.sync();
        await().atMost(5, SECONDS).until(() -> fake.opened.size() == 1);
        fake.ends.get(0).accept(null);
        await().atMost(5, SECONDS).until(() -> fake.opened.size() == 2);
        watcher.shutdown();
    }

    @Test
    void deletedHostWorkerIsStopped() {
        watcher.sync();
        await().atMost(5, SECONDS).until(() -> fake.opened.size() == 1);
        when(hosts.findAll()).thenReturn(List.of());
        watcher.sync();
        await().atMost(5, SECONDS).until(() -> fake.closed.get() >= 1);
    }

    private static final class FakeEvents implements DockerEventService {
        final List<Consumer<DockerEventDto>> opened = new CopyOnWriteArrayList<>();
        final List<Consumer<Throwable>> ends = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();
        volatile int failFirst;

        @Override
        public Closeable stream(UUID hostId, DockerEndpoint endpoint, Consumer<DockerEventDto> onEvent) {
            return stream(hostId, endpoint, onEvent, e -> { });
        }

        @Override
        public synchronized Closeable stream(UUID hostId, DockerEndpoint endpoint, Consumer<DockerEventDto> onEvent,
                                             Consumer<Throwable> onEnd) {
            if (failFirst-- > 0) {
                throw new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null);
            }
            opened.add(onEvent);
            ends.add(onEnd);
            return closed::incrementAndGet;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
