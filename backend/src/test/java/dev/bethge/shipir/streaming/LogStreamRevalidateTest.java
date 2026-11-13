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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link LogStreamHandler#revalidate}: a throwing predicate fails closed for that stream only. */
class LogStreamRevalidateTest {

    private final ContainerLogService logs = mock(ContainerLogService.class);
    private final DockerHostRepository hosts = mock(DockerHostRepository.class);
    private final LogStreamHandler handler = new LogStreamHandler(logs, hosts, mock(HostEndpointResolver.class),
            mock(StompFrameSender.class), new LogStreamProperties(200, 500, 8192, 1000, java.time.Duration.ofSeconds(5)), Clock.systemUTC(),
            mock(StreamViewers.class));

    @Test
    void throwingPredicateDropsThatStreamAndStillChecksTheOthers() throws Exception {
        when(hosts.findById(any())).thenReturn(Optional.of(mock(DockerHost.class)));
        when(logs.follow(any(), any(), any(), anyInt(), anyBoolean(), any(), any())).thenReturn(mock(Closeable.class));
        var host = UUID.randomUUID();
        var options = new LogStreamHandler.Options(100, false);
        var broken = new LogStreamHandler.Target(host, "broken");
        var gone = new LogStreamHandler.Target(host, "gone");
        var kept = new LogStreamHandler.Target(host, "kept");
        handler.subscribe(broken, options, "alice", "s1", "a");
        handler.subscribe(gone, options, "alice", "s1", "b");
        handler.subscribe(kept, options, "alice", "s1", "c");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(logs, times(3))
                .follow(any(), any(), any(), anyInt(), anyBoolean(), any(), any()));

        handler.revalidate("alice", t -> {
            if (t.equals(broken)) {
                throw new IllegalStateException("docker unreachable");
            }
            return t.equals(kept);
        });

        assertThat(handler.subscriberCount(broken)).isZero();
        assertThat(handler.subscriberCount(gone)).isZero();
        assertThat(handler.subscriberCount(kept)).isEqualTo(1);
    }

    @Test
    void revalidateAllChecksEverySubscriberAndDropsOnlyTheOnesNoLongerAllowed() throws Exception {
        when(hosts.findById(any())).thenReturn(Optional.of(mock(DockerHost.class)));
        when(logs.follow(any(), any(), any(), anyInt(), anyBoolean(), any(), any())).thenReturn(mock(Closeable.class));
        var host = UUID.randomUUID();
        var options = new LogStreamHandler.Options(100, false);
        var target = new LogStreamHandler.Target(host, "c1");
        var broken = new LogStreamHandler.Target(host, "c2");
        handler.subscribe(target, options, "grantOnly", "s1", "a");
        handler.subscribe(target, options, "wholeHost", "s2", "b");
        handler.subscribe(broken, options, "wholeHost", "s2", "c");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(logs, times(2))
                .follow(any(), any(), any(), anyInt(), anyBoolean(), any(), any()));

        handler.revalidateAll((user, t) -> {
            if (t.equals(broken)) {
                throw new IllegalStateException("docker unreachable");
            }
            return !user.equals("grantOnly");
        });

        assertThat(handler.subscriberCount(target)).isEqualTo(1); // only wholeHost stays
        assertThat(handler.subscriberCount(broken)).isZero(); // fail closed
    }
}
