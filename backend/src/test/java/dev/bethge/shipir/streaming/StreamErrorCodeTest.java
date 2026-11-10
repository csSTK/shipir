package dev.bethge.shipir.streaming;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerLogService;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ContainerStatsService;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A stored host's TLS/permission/timeout failure must not reach a non-admin in an error frame (it would tell which of
 * the admin-chosen targets listens and how); ADMIN keeps the detail. Streams are shared, so the code is chosen per
 * subscriber, including on replay of the buffered frame to a late subscriber.
 */
class StreamErrorCodeTest {

    private final UUID host = UUID.randomUUID();
    private final DockerHostRepository hosts = mock(DockerHostRepository.class);
    private final HostEndpointResolver endpoints = mock(HostEndpointResolver.class);
    private final StompFrameSender sender = mock(StompFrameSender.class);
    private final StreamViewers viewers = mock(StreamViewers.class);

    {
        when(hosts.findById(any())).thenReturn(Optional.of(mock(DockerHost.class)));
        when(viewers.isAdmin("root")).thenReturn(true);
        when(viewers.isAdmin("viewer")).thenReturn(false);
    }

    private String lastPayloadTo(String session) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sender, atLeastOnce()).send(eq(session), any(), any(), payload.capture());
        return payload.getValue().toString();
    }

    @Test
    void containerLogErrorFrameIsUnreachableForViewerAndDetailedForAdmin() throws Exception {
        ContainerLogService logs = mock(ContainerLogService.class);
        when(logs.follow(any(), any(), any(), anyInt(), anyBoolean(), any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.TLS_ERROR, "x", null));
        var handler = new LogStreamHandler(logs, hosts, endpoints, sender,
                new LogStreamProperties(200, 500, 8192, 1000), Clock.systemUTC(), viewers);
        var target = new LogStreamHandler.Target(host, "web");
        var options = new LogStreamHandler.Options(100, false);

        handler.subscribe(target, options, "root", "s-root", "1");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(lastPayloadTo("s-root")).contains("TLS_ERROR"));
        // late subscriber: the stream already ended, so it gets a fresh follow, which fails the same way; the viewer
        // sees the collapsed code, not the raw one
        handler.subscribe(target, options, "viewer", "s-viewer", "1");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(lastPayloadTo("s-viewer")).contains("UNREACHABLE").doesNotContain("TLS_ERROR"));
        assertThat(lastPayloadTo("s-root")).contains("TLS_ERROR");
    }

    @Test
    void containerLogErrorFrameKeepsPostOperationAndNotFoundCodesForViewer() throws Exception {
        for (DockerErrorCode kept : new DockerErrorCode[]{DockerErrorCode.INVALID_STATE, DockerErrorCode.NOT_FOUND}) {
            ContainerLogService logs = mock(ContainerLogService.class);
            when(logs.follow(any(), any(), any(), anyInt(), anyBoolean(), any(), any()))
                    .thenThrow(new DockerConnectionException(kept, "x", null));
            var handler = new LogStreamHandler(logs, hosts, endpoints, sender,
                    new LogStreamProperties(200, 500, 8192, 1000), Clock.systemUTC(), viewers);
            String session = "s-" + kept;
            handler.subscribe(new LogStreamHandler.Target(host, "web"), new LogStreamHandler.Options(100, false),
                    "viewer", session, "1");
            await().atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(lastPayloadTo(session)).contains(kept.name()));
        }
    }

    @Test
    void deploymentLogProjectErrorIsUnreachableForViewerAndDetailedForAdmin() {
        ContainerService containers = mock(ContainerService.class);
        when(containers.listContainers(any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.PERMISSION_DENIED, "x", null));
        var service = new DeploymentLogStreamService(containers, mock(ContainerLogService.class), hosts, endpoints,
                sender, new DeploymentLogProperties(100, 10, 8192, 100, 1000, 100, Duration.ofHours(1)),
                Clock.systemUTC(), viewers);
        var target = new DeploymentLogStreamService.Target(host, "shop");
        var options = new DeploymentLogStreamService.Options(100, false);

        service.subscribe(target, options, "root", "s-root", "1");
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(lastPayloadTo("s-root")).contains("PERMISSION_DENIED"));
        service.subscribe(target, options, "viewer", "s-viewer", "1");
        assertThat(lastPayloadTo("s-viewer")).contains("UNREACHABLE").doesNotContain("PERMISSION_DENIED");
        assertThat(lastPayloadTo("s-root")).contains("PERMISSION_DENIED");
    }

    @Test
    void deploymentLogKeepsItsOwnProjectCodesForViewer() {
        ContainerService containers = mock(ContainerService.class);
        when(containers.listContainers(any(), any())).thenThrow(new IllegalStateException("boom"));
        var service = new DeploymentLogStreamService(containers, mock(ContainerLogService.class), hosts, endpoints,
                sender, new DeploymentLogProperties(100, 10, 8192, 100, 1000, 100, Duration.ofHours(1)),
                Clock.systemUTC(), viewers);
        service.subscribe(new DeploymentLogStreamService.Target(host, "shop"),
                new DeploymentLogStreamService.Options(100, false), "viewer", "s-viewer", "1");
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(lastPayloadTo("s-viewer")).contains("UNAVAILABLE"));
    }

    @Test
    void statsErrorFrameIsUnreachableForViewerAndDetailedForAdmin() {
        ContainerService containers = mock(ContainerService.class);
        when(containers.listContainers(any(), any()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.TIMEOUT, "x", null));
        StreamAccess access = mock(StreamAccess.class);
        when(access.roleOf("root")).thenReturn(Optional.of(Role.ADMIN));
        when(access.roleOf("viewer")).thenReturn(Optional.of(Role.VIEWER));
        var service = new ContainerStatsStreamService(containers, mock(ContainerStatsService.class), hosts, endpoints,
                sender, new ContainerStatsProperties(Duration.ofSeconds(30), 10), access);

        service.subscribe(host, "root", "s-root", "1");
        service.subscribe(host, "viewer", "s-viewer", "1");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(lastPayloadTo("s-root")).contains("TIMEOUT");
            assertThat(lastPayloadTo("s-viewer")).contains("UNREACHABLE").doesNotContain("TIMEOUT");
        });
    }

    @Test
    void demotedAdminGetsCollapsedCodesOnContainerLogs() throws Exception {
        ContainerLogService logs = mock(ContainerLogService.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Consumer<Throwable>> onEnd = ArgumentCaptor.forClass(java.util.function.Consumer.class);
        when(logs.follow(any(), any(), any(), anyInt(), anyBoolean(), any(), onEnd.capture()))
                .thenReturn(mock(java.io.Closeable.class));
        var handler = new LogStreamHandler(logs, hosts, endpoints, sender,
                new LogStreamProperties(200, 500, 8192, 1000), Clock.systemUTC(), viewers);
        handler.subscribe(new LogStreamHandler.Target(host, "web"), new LogStreamHandler.Options(100, false),
                "root", "s-root", "1");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !onEnd.getAllValues().isEmpty());

        handler.refreshAdmin("root", false);
        onEnd.getValue().accept(new DockerConnectionException(DockerErrorCode.TLS_ERROR, "x", null));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(lastPayloadTo("s-root")).contains("UNREACHABLE").doesNotContain("TLS_ERROR"));
    }

    @Test
    void demotedAdminGetsCollapsedCodesOnProjectLogs() throws Exception {
        ContainerService containers = mock(ContainerService.class);
        var c = mock(dev.bethge.shipir.docker.dto.ContainerDto.class);
        when(c.id()).thenReturn("c1");
        when(c.state()).thenReturn("running");
        when(c.composeProject()).thenReturn("shop");
        when(c.composeService()).thenReturn("web");
        when(c.name()).thenReturn("shop-web-1");
        when(containers.listContainers(any(), any())).thenReturn(java.util.List.of(c));
        ContainerLogService logs = mock(ContainerLogService.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Consumer<Throwable>> onEnd = ArgumentCaptor.forClass(java.util.function.Consumer.class);
        when(logs.follow(any(), any(), any(), anyInt(), anyBoolean(), any(), onEnd.capture()))
                .thenReturn(mock(java.io.Closeable.class));
        var service = new DeploymentLogStreamService(containers, logs, hosts, endpoints,
                sender, new DeploymentLogProperties(100, 10, 8192, 100, 1000, 100, Duration.ofHours(1)),
                Clock.systemUTC(), viewers);
        service.subscribe(new DeploymentLogStreamService.Target(host, "shop"),
                new DeploymentLogStreamService.Options(100, false), "root", "s-root", "1");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !onEnd.getAllValues().isEmpty());

        service.refreshAdmin("root", false);
        onEnd.getValue().accept(new DockerConnectionException(DockerErrorCode.TLS_ERROR, "x", null));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(lastPayloadTo("s-root")).contains("UNREACHABLE").doesNotContain("TLS_ERROR"));
    }
}
