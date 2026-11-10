package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.client.DockerSystemService;
import dev.bethge.shipir.docker.dto.DockerSystemInfoDto;
import dev.bethge.shipir.host.dto.HostOverviewResponse.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Stubbed Docker port, in-memory repository mock and fixed clock: no Spring context, no database. */
class HostHealthCheckSchedulerTest {

    private final DockerHostRepository repository = mock(DockerHostRepository.class);
    private final DockerSystemService docker = mock(DockerSystemService.class);
    private final DockerHostConnectionManager connections = mock(DockerHostConnectionManager.class);
    private final Instant now = Instant.parse("2026-01-01T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final List<Object> events = new ArrayList<>();
    private final ApplicationEventPublisher publisher = events::add;
    private final Map<String, Boolean> failing = new ConcurrentHashMap<>();
    private final List<DockerHost> stored = new ArrayList<>();
    private HostOverviewService overview;
    private HostHealthCheckScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(connections.localEndpoint()).thenReturn(new DockerEndpoint.Local("/var/run/docker.sock"));
        when(repository.findAll()).thenAnswer(inv -> List.copyOf(stored));
        HostEndpointResolver resolver = mock(HostEndpointResolver.class);
        when(resolver.endpointOf(any())).thenAnswer(inv -> new DockerEndpoint.Local(((DockerHost) inv.getArgument(0)).getName()));
        when(docker.info(any(), any())).thenAnswer(inv -> {
            String name = ((DockerEndpoint.Local) inv.getArgument(1)).socketPath();
            if ("hang".equals(name)) {
                Thread.sleep(60_000);
            }
            if (failing.containsKey(name)) {
                throw new DockerConnectionException(DockerErrorCode.UNREACHABLE, "refused", null);
            }
            return new DockerSystemInfoDto("27.0.1", 4, 1L, 1, 0, 0, 0, 0, 0);
        });
        overview = new HostOverviewService(repository, resolver, docker, connections,
                new HostOverviewProperties(Duration.ofSeconds(1), Duration.ofSeconds(60), true),
                mock(dev.bethge.shipir.permission.CurrentAccess.class), mock(HostTagService.class)); // unstubbed = no filtering
        scheduler = new HostHealthCheckScheduler(overview, publisher, clock);
    }

    @AfterEach
    void tearDown() {
        overview.shutdown();
    }

    private DockerHost host(String name) {
        DockerHost h = mock(DockerHost.class);
        when(h.getId()).thenReturn(UUID.randomUUID());
        when(h.getName()).thenReturn(name);
        when(h.getConnectionType()).thenReturn(ConnectionType.LOCAL);
        stored.add(h);
        return h;
    }

    @Test
    void checkRunsInsideTheSystemScope() {
        var access = mock(dev.bethge.shipir.permission.CurrentAccess.class);
        List<Boolean> scope = new ArrayList<>();
        when(access.visibleHostIds()).thenAnswer(inv -> {
            scope.add(dev.bethge.shipir.common.security.SystemScope.active());
            return java.util.Optional.empty();
        });
        overview.shutdown();
        overview = new HostOverviewService(repository, mock(HostEndpointResolver.class), docker, connections,
                new HostOverviewProperties(Duration.ofSeconds(1), Duration.ofSeconds(60), true), access,
                mock(HostTagService.class));
        scheduler = new HostHealthCheckScheduler(overview, publisher, clock);

        scheduler.check();

        assertThat(scope).containsExactly(true);
    }

    @Test
    void baselineEmitsNothing() {
        host("a");
        failing.put("b", true);
        host("b");
        scheduler.check();
        assertThat(events).isEmpty();
    }

    @Test
    void transitionsEmitExactlyOneEventEachAndSteadyStateNone() {
        DockerHost a = host("a");
        scheduler.check();

        failing.put("a", true);
        scheduler.check();
        scheduler.check();
        assertThat(events).singleElement().isInstanceOfSatisfying(HostStatusChangedEvent.class, e -> {
            assertThat(e.hostId()).isEqualTo(a.getId());
            assertThat(e.hostName()).isEqualTo("a");
            assertThat(e.previous()).isEqualTo(Status.UP);
            assertThat(e.current()).isEqualTo(Status.DOWN);
            assertThat(e.errorCode()).isEqualTo("UNREACHABLE");
            assertThat(e.occurredAt()).isEqualTo(now);
        });

        failing.clear();
        scheduler.check();
        scheduler.check();
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOfSatisfying(HostStatusChangedEvent.class, e -> {
            assertThat(e.previous()).isEqualTo(Status.DOWN);
            assertThat(e.current()).isEqualTo(Status.UP);
            assertThat(e.errorCode()).isNull();
        });
    }

    @Test
    void deletedHostIsForgottenSoRecreationIsANewBaseline() {
        DockerHost a = host("a");
        scheduler.check();
        stored.remove(a);
        scheduler.check();
        failing.put("a", true);
        stored.add(a);
        scheduler.check();
        assertThat(events).isEmpty();
    }

    @Test
    void hangingHostDoesNotBlockOthers() {
        host("a");
        host("hang");
        scheduler.check();
        failing.put("a", true);
        long start = System.nanoTime();
        scheduler.check();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(events).singleElement().isInstanceOfSatisfying(HostStatusChangedEvent.class,
                e -> assertThat(e.hostName()).isEqualTo("a"));
    }

    @Test
    void failingListenerForOneHostDoesNotStopTheOtherHosts() {
        host("a");
        host("b");
        scheduler.check();
        List<Object> published = new ArrayList<>();
        scheduler = new HostHealthCheckScheduler(overview, e -> {
            published.add(e);
            if (published.size() == 1) {
                throw new IllegalStateException("listener failed");
            }
        }, clock);
        scheduler.check(); // baseline for the new scheduler
        failing.put("a", true);
        failing.put("b", true);

        scheduler.check();

        assertThat(published).hasSize(2);
    }
}
