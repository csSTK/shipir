package dev.bethge.shipir.streaming;

import dev.bethge.shipir.docker.client.ContainerLogService;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link LogStreamHandler#dropHost}: a deleted host loses all log subscriptions, other hosts keep theirs. */
class LogStreamDropHostTest {

    private final ContainerLogService logs = mock(ContainerLogService.class);
    private final DockerHostRepository hosts = mock(DockerHostRepository.class);
    private final HostEndpointResolver endpoints = mock(HostEndpointResolver.class);
    private final Closeable followA = mock(Closeable.class);
    private final Closeable followB = mock(Closeable.class);
    private final UUID hostA = UUID.randomUUID();
    private final UUID hostB = UUID.randomUUID();
    private final LogStreamHandler handler = new LogStreamHandler(logs, hosts, endpoints,
            mock(StompFrameSender.class), new LogStreamProperties(200, 500, 8192, 1000, java.time.Duration.ofSeconds(5)), Clock.systemUTC(),
            mock(StreamViewers.class));

    @Test
    void dropHostEndsAllSubscriptionsOfThatHostOnly() throws Exception {
        when(hosts.findById(any())).thenReturn(Optional.of(mock(DockerHost.class)));
        when(logs.follow(any(), any(), any(), anyInt(), anyBoolean(), any(), any())).thenAnswer(inv ->
                hostA.equals(inv.getArgument(0)) ? followA : followB);
        var options = new LogStreamHandler.Options(100, false);
        var a1 = new LogStreamHandler.Target(hostA, "web");
        var a2 = new LogStreamHandler.Target(hostA, "db");
        var b1 = new LogStreamHandler.Target(hostB, "web");
        handler.subscribe(a1, options, "alice", "s1", "x");
        handler.subscribe(a1, options, "bob", "s2", "x");
        handler.subscribe(a2, options, "alice", "s1", "y");
        handler.subscribe(b1, options, "alice", "s1", "z");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(logs, org.mockito.Mockito.times(3))
                .follow(any(), any(), any(), anyInt(), anyBoolean(), any(), any()));

        handler.dropHost(hostA);

        assertThat(handler.subscriberCount(a1)).isZero();
        assertThat(handler.subscriberCount(a2)).isZero();
        assertThat(handler.subscriberCount(b1)).isEqualTo(1);
        assertThat(handler.activeStreams()).isEqualTo(1);
        verify(followA, timeout(5000).times(2)).close();
        org.mockito.Mockito.verify(followB, org.mockito.Mockito.never()).close();
    }
}
