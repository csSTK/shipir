package dev.bethge.shipir.streaming;

import dev.bethge.shipir.docker.client.ContainerLogService;
import dev.bethge.shipir.docker.dto.LogLine;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.Closeable;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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

/** A shared log stream that has ended (container stopped) is not reused: a later viewer gets a fresh follow. */
class LogStreamEndedReplaceTest {

    private final ContainerLogService logs = mock(ContainerLogService.class);
    private final DockerHostRepository hosts = mock(DockerHostRepository.class);
    private final StompFrameSender sender = mock(StompFrameSender.class);
    private final LogStreamHandler handler = new LogStreamHandler(logs, hosts, mock(HostEndpointResolver.class), sender,
            new LogStreamProperties(200, 500, 8192, 1000), Clock.systemUTC(), mock(StreamViewers.class));

    private final List<Consumer<LogLine>> lineSinks = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> endSinks = new CopyOnWriteArrayList<>();

    @Test
    void joiningAnEndedStreamStartsAFreshFollowThatDeliversLiveLines() throws Exception {
        when(hosts.findById(any())).thenReturn(Optional.of(mock(DockerHost.class)));
        when(logs.follow(any(), any(), any(), anyInt(), anyBoolean(), any(), any())).thenAnswer(inv -> {
            lineSinks.add(inv.getArgument(5));
            endSinks.add(inv.getArgument(6));
            return mock(Closeable.class);
        });
        var target = new LogStreamHandler.Target(UUID.randomUUID(), "web");
        var options = new LogStreamHandler.Options(100, false);

        handler.subscribe(target, options, "alice", "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> endSinks.size() == 1);
        endSinks.get(0).accept(null); // container stopped, alice stays subscribed

        handler.subscribe(target, options, "bob", "s2", "b"); // container restarted, new viewer
        await().atMost(5, TimeUnit.SECONDS).until(() -> lineSinks.size() == 2);
        lineSinks.get(1).accept(new LogLine(dev.bethge.shipir.docker.dto.LogLine.Stream.STDOUT, "after restart", null));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sender, atLeastOnce()).send(eq("s2"), eq("b"), any(), payload.capture());
        List<String> lines = new ArrayList<>();
        payload.getAllValues().forEach(p -> lines.add(((LogStreamMessage) p).line()));
        assertThat(lines).contains("after restart");
        assertThat(handler.subscriberCount(target)).isEqualTo(1);
    }
}
