package dev.bethge.shipir.proxy;

import dev.bethge.shipir.common.security.SystemScope;
import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.DockerEventService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.DockerEventDto;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import dev.bethge.shipir.MutableClock;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the reconciler attaches, ignores and survives; Docker and the repositories are stubbed, the drain runs inline. */
class ProxyRouteReconcilerTest {

    private static final UUID HOST = UUID.randomUUID();
    private static final DockerEndpoint ENDPOINT = new DockerEndpoint.Local("unix:///var/run/docker.sock");

    private final DockerHostRepository hosts = mock(DockerHostRepository.class);
    private final HostEndpointResolver endpoints = mock(HostEndpointResolver.class);
    private final ProxyInstanceRepository instances = mock(ProxyInstanceRepository.class);
    private final ProxyRouteRepository routes = mock(ProxyRouteRepository.class);
    private final ProxySyncService sync = mock(ProxySyncService.class);
    private final NetworkService networks = mock(NetworkService.class);
    private final ContainerService containers = mock(ContainerService.class);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private ProxyRouteReconciler reconciler;
    private final List<String> order = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        reconciler = new ProxyRouteReconciler(hosts, endpoints, mock(DockerEventService.class), instances, routes, sync,
                networks, containers, Runnable::run, clock, Duration.ofSeconds(30));
        DockerHost host = mock(DockerHost.class);
        when(hosts.findById(HOST)).thenReturn(Optional.of(host));
        when(endpoints.endpointOf(host)).thenReturn(ENDPOINT);
        when(instances.findByHostId(HOST)).thenReturn(Optional.of(
                ProxyInstance.create(HOST, ProxyType.CADDY, "ops@example.com", CertMode.ACME_HTTP)));
        when(routes.findByHostId(HOST)).thenReturn(List.of(route("web")));
        when(sync.exclusive(any(), any())).thenAnswer(inv -> {
            order.add("lock");
            return ((Supplier<Object>) inv.getArgument(1)).get();
        });
    }

    private static ProxyRoute route(String container) {
        return ProxyRoute.create(HOST, container + ".example.com", List.of(), container, 80, BackendScheme.HTTP, null,
                null, CertMode.ACME_HTTP, null, true, Map.of(), Map.of());
    }

    private static DockerEventDto start(String name, String id, String project) {
        return new DockerEventDto(DockerEventDto.Type.CONTAINER, "start", id, name, project, Instant.now());
    }

    @Test
    void startOfARoutedContainerAttachesItToTheProxyNetwork() {
        AtomicBoolean asSystem = new AtomicBoolean();
        doAnswer(inv -> {
            asSystem.set(SystemScope.active());
            return null;
        }).when(networks).connectContainer(HOST, ENDPOINT, "shipir-proxy", "c1");

        reconciler.handle(HOST, start("web", "c1", "myapp"));

        verify(networks).connectContainer(HOST, ENDPOINT, "shipir-proxy", "c1");
        assertThat(asSystem).as("runs as the system, not a user").isTrue();
        assertThat(order).as("under the per-host proxy lock").containsExactly("lock");
    }

    @Test
    void nonRoutedContainersAndOtherEventsAreIgnored() {
        reconciler.handle(HOST, start("db", "c2", "myapp"));
        reconciler.handle(HOST, new DockerEventDto(DockerEventDto.Type.CONTAINER, "die", "c1", "web", null, Instant.now()));
        reconciler.handle(HOST, new DockerEventDto(DockerEventDto.Type.NETWORK, "start", "n1", "web", null, Instant.now()));

        verify(networks, never()).connectContainer(any(), any(), anyString(), anyString());
        verify(sync, never()).sync(any());
    }

    @Test
    void repeatedEventsAttachEachTimeThroughTheIdempotentCallAndNeverFail() {
        reconciler.handle(HOST, start("web", "c1", null));
        reconciler.handle(HOST, start("web", "c1", null));

        // connectContainer is idempotent by contract (already connected = no-op), so a repeat is harmless
        verify(networks, times(2)).connectContainer(HOST, ENDPOINT, "shipir-proxy", "c1");
    }

    @Test
    void nothingHappensWithoutAManagedProxy() {
        when(instances.findByHostId(HOST)).thenReturn(Optional.empty());

        reconciler.handle(HOST, start("web", "c1", null));
        reconciler.requestFullPass(HOST);

        verify(networks, never()).connectContainer(any(), any(), anyString(), anyString());
        verify(containers, never()).listContainers(any(), any());
    }

    @Test
    void aFailureIsSwallowedAndTheNextEventStillWorks() {
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "secret-detail token=abc", null))
                .doThrow(new IllegalStateException("password=hunter2"))
                .doNothing()
                .when(networks).connectContainer(HOST, ENDPOINT, "shipir-proxy", "c1");

        reconciler.handle(HOST, start("web", "c1", null));
        reconciler.handle(HOST, start("web", "c1", null));
        reconciler.handle(HOST, start("web", "c1", null));

        verify(networks, times(3)).connectContainer(HOST, ENDPOINT, "shipir-proxy", "c1");
    }

    @Test
    void aVanishedContainerOrMissingProxyNetworkIsSkipped() {
        doThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "no such network", null))
                .when(networks).connectContainer(HOST, ENDPOINT, "shipir-proxy", "c1");

        reconciler.handle(HOST, start("web", "c1", null));

        verify(networks).connectContainer(HOST, ENDPOINT, "shipir-proxy", "c1");
    }

    @Test
    void fullPassAttachesEveryRoutedContainerButNotTheProxyItself() {
        when(routes.findByHostId(HOST)).thenReturn(List.of(route("web"), route("api")));
        when(containers.listContainers(HOST, ENDPOINT)).thenReturn(List.of(
                container("w", "web", null), container("a", "api", null), container("d", "db", null),
                container("p", "web", "shipir-proxy")));

        reconciler.requestFullPass(HOST);

        verify(networks).connectContainer(HOST, ENDPOINT, "shipir-proxy", "w");
        verify(networks).connectContainer(HOST, ENDPOINT, "shipir-proxy", "a");
        verify(networks, never()).connectContainer(eq(HOST), any(), anyString(), eq("d"));
        verify(networks, never()).connectContainer(eq(HOST), any(), anyString(), eq("p"));
    }

    @Test
    void dockerBeingDownDuringAFullPassDoesNotThrow() {
        when(containers.listContainers(HOST, ENDPOINT))
                .thenThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "x", null));

        reconciler.requestFullPass(HOST);

        verify(networks, never()).connectContainer(any(), any(), anyString(), anyString());
    }

    @Test
    void startOfTheProxyItselfSyncsItsConfigAgainAndAFailureStaysContained() {
        doThrow(new ProxyException(org.springframework.http.HttpStatus.BAD_GATEWAY, "PROXY_SYNC_FAILED", "boom"))
                .when(sync).sync(HOST);

        reconciler.handle(HOST, start("shipir-proxy-caddy-1", "p1", "shipir-proxy"));

        verify(sync).sync(HOST);
        verify(networks, never()).connectContainer(any(), any(), anyString(), anyString());
    }

    @Test
    void proxyStartEventsWithinTheWindowSyncOnceAndOneAfterItSyncsAgain() {
        for (int i = 0; i < 10; i++) {
            reconciler.handle(HOST, start("shipir-proxy-caddy-1", "p1", "shipir-proxy"));
            clock.advance(Duration.ofSeconds(2));
        }
        verify(sync, times(1)).sync(HOST);

        clock.advance(Duration.ofSeconds(30));
        reconciler.handle(HOST, start("shipir-proxy-caddy-1", "p1", "shipir-proxy"));
        verify(sync, times(2)).sync(HOST);
    }

    @Test
    void failuresAreLoggedWithoutMessagesOrSecrets() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProxyRouteReconciler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "secret-detail token=abc", null))
                    .doThrow(new IllegalStateException("password=hunter2"))
                    .when(networks).connectContainer(HOST, ENDPOINT, "shipir-proxy", "c1");
            reconciler.handle(HOST, start("web", "c1", null));
            reconciler.handle(HOST, start("web", "c1", null));
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).isNotEmpty();
        String output = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertThat(output).doesNotContain("hunter2", "token=abc", "secret-detail")
                .contains("UNREACHABLE", "IllegalStateException");
        assertThat(appender.list).allMatch(e -> e.getThrowableProxy() == null);
    }

    @Test
    void anEventStormCollapsesIntoOnePassWhileADrainIsRunning() {
        List<Runnable> queued = new ArrayList<>();
        ProxyRouteReconciler deferred = new ProxyRouteReconciler(hosts, endpoints, mock(DockerEventService.class),
                instances, routes, sync, networks, containers, queued::add, clock, Duration.ofSeconds(30));

        for (int i = 0; i < 50; i++) {
            deferred.handle(HOST, start("web", "c1", null));
        }

        assertThat(queued).as("one drain per host").hasSize(1);
        queued.get(0).run();
        verify(networks, times(1)).connectContainer(HOST, ENDPOINT, "shipir-proxy", "c1");
    }

    private static ContainerDto container(String id, String name, String project) {
        return new ContainerDto(id, name, "img", "running", "Up", null, Instant.now(), List.of(), project, null);
    }

    @Test
    void aHostWhoseProxyIsGoneIsForgottenByTheWorkerCleanup() {
        reconciler.handle(HOST, start("shipir-proxy-caddy-1", "p1", "shipir-proxy"));
        verify(sync).sync(HOST);
        assertThat(lastProxySyncOf()).containsKey(HOST);
        when(instances.findAll()).thenReturn(List.of());

        reconciler.syncWorkers();

        verify(sync).forget(HOST);
        assertThat(lastProxySyncOf()).as("rate-limit entry dropped with the proxy").isEmpty();
    }

    @Test
    void aDeletedHostIsForgottenByTheWorkerCleanupEvenIfItsProxyRowStillExists() {
        reconciler.handle(HOST, start("shipir-proxy-caddy-1", "p1", "shipir-proxy"));
        when(instances.findAll()).thenReturn(List.of(
                ProxyInstance.create(HOST, ProxyType.CADDY, "ops@example.com", CertMode.ACME_HTTP)));
        when(hosts.existsById(HOST)).thenReturn(false);

        reconciler.syncWorkers();

        verify(sync).forget(HOST);
        assertThat(lastProxySyncOf()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Instant> lastProxySyncOf() {
        return (Map<UUID, Instant>) ReflectionTestUtils.getField(reconciler, "lastProxySync");
    }

}
