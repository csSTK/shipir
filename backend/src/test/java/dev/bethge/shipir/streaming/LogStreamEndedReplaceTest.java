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
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.Instant;
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
            new LogStreamProperties(200, 500, 8192, 1000, Duration.ofSeconds(5)), Clock.systemUTC(), mock(StreamViewers.class));

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
        assertThat(handler.subscriberCount(target)).isEqualTo(2); // alice moved to the fresh stream (shipr-0qbh.91)
    }

    /** shipr-0qbh.91: viewers of the replaced stream move to the fresh one, so revalidation and live lines reach them. */
    @Test
    void viewersOfTheReplacedStreamMoveToTheFreshOneAndAreRevalidated() throws Exception {
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
        endSinks.get(0).accept(null);
        handler.subscribe(target, options, "bob", "s2", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> lineSinks.size() == 2);

        assertThat(handler.subscriberCount(target)).isEqualTo(2); // alice moved along
        lineSinks.get(1).accept(new LogLine(LogLine.Stream.STDOUT, "after restart", null));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sender, atLeastOnce()).send(eq("s1"), eq("a"), any(), payload.capture());
        List<LogStreamMessage> got = new ArrayList<>();
        payload.getAllValues().forEach(p -> got.add((LogStreamMessage) p));
        LogStreamMessage ended = got.stream().filter(m -> m.type() == LogStreamMessage.Type.STATUS).findFirst().orElseThrow();
        LogStreamMessage fresh = got.stream().filter(m -> "after restart".equals(m.line())).findFirst().orElseThrow();
        assertThat(fresh.seq()).isGreaterThan(ended.seq()); // seq goes on, a client that dedupes by seq still sees it

        handler.revalidate("alice", t -> false); // access revoked: alice is reached
        assertThat(handler.subscriberCount(target)).isEqualTo(1);
    }

    /** The 8-arg follow (with since); records tail and since, collects sinks like the 7-arg one. */
    private void mockFollows(List<Integer> tails, List<Instant> sinces) {
        when(hosts.findById(any())).thenReturn(Optional.of(mock(DockerHost.class)));
        when(logs.follow(any(), any(), any(), anyInt(), anyBoolean(), any(), any())).thenAnswer(inv -> {
            tails.add(inv.getArgument(3));
            sinces.add(null);
            lineSinks.add(inv.getArgument(5));
            endSinks.add(inv.getArgument(6));
            return mock(Closeable.class);
        });
        when(logs.follow(any(), any(), any(), anyInt(), any(Instant.class), anyBoolean(), any(), any())).thenAnswer(inv -> {
            tails.add(inv.getArgument(3));
            sinces.add(inv.getArgument(4));
            lineSinks.add(inv.getArgument(6));
            endSinks.add(inv.getArgument(7));
            return mock(Closeable.class);
        });
    }

    private List<String> linesTo(String session, String sub) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sender, atLeastOnce()).send(eq(session), eq(sub), any(), payload.capture());
        return payload.getAllValues().stream().map(p -> ((LogStreamMessage) p).line()).filter(l -> l != null).toList();
    }

    /**
     * shipr-0qbh.93: carried-over viewers must not get the tail replayed again and must not lose lines written
     * after the old stream ended; a joiner still sees recent lines.
     */
    @Test
    void freshFollowForCarriedOverViewersUsesSinceInsteadOfTailAndJoinerGetsBufferedLines() throws Exception {
        List<Integer> tails = new CopyOnWriteArrayList<>();
        List<Instant> sinces = new CopyOnWriteArrayList<>();
        mockFollows(tails, sinces);
        var target = new LogStreamHandler.Target(UUID.randomUUID(), "web");
        var options = new LogStreamHandler.Options(100, false);

        Instant before = Instant.now();
        handler.subscribe(target, options, "alice", "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> endSinks.size() == 1);
        lineSinks.get(0).accept(new LogLine(LogLine.Stream.STDOUT, "old1", null));
        lineSinks.get(0).accept(new LogLine(LogLine.Stream.STDOUT, "old2", null));
        endSinks.get(0).accept(null);

        handler.subscribe(target, options, "bob", "s2", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> lineSinks.size() == 2);

        assertThat(tails).containsExactly(100, -1);
        assertThat(sinces.get(0)).isNull();
        assertThat(sinces.get(1)).isBetween(before.minusSeconds(5), Instant.now()); // since = end of the old stream minus the safety margin
        // lines logged after the old stream ended (e.g. restart output) come with the since-follow
        lineSinks.get(1).accept(new LogLine(LogLine.Stream.STDOUT, "restart", null));

        assertThat(linesTo("s1", "a")).containsExactly("old1", "old2", "restart"); // each exactly once
        assertThat(linesTo("s2", "b")).containsExactly("old1", "old2", "restart");
    }

    /** A stream that ended without ever delivering a line (follow failed): the requested tail stays. */
    @Test
    void endedStreamWithoutLinesKeepsTheRequestedTail() throws Exception {
        List<Integer> tails = new CopyOnWriteArrayList<>();
        List<Instant> sinces = new CopyOnWriteArrayList<>();
        mockFollows(tails, sinces);
        var target = new LogStreamHandler.Target(UUID.randomUUID(), "web");
        var options = new LogStreamHandler.Options(500, false);

        handler.subscribe(target, options, "alice", "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> endSinks.size() == 1);
        endSinks.get(0).accept(new RuntimeException("boom")); // ERROR, no line delivered

        handler.subscribe(target, options, "bob", "s2", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> lineSinks.size() == 2);

        assertThat(tails).containsExactly(500, 500);
        assertThat(sinces).containsOnlyNulls();
    }

    /**
     * shipr-0qbh.98: Docker evaluates {@code since} against the daemon clock. With a daemon clock behind Shipir's, a
     * since taken from the Shipir clock skips lines written right after the end; the safety margin must cover it.
     */
    @Test
    void sinceIsBackedOffByTheSafetyMarginSoABehindDaemonClockLosesNoLines() throws Exception {
        Instant shipirNow = Instant.parse("2026-11-13T12:00:00Z");
        Duration skew = Duration.ofSeconds(3); // daemon clock is 3s behind Shipir
        var skewed = new LogStreamHandler(logs, hosts, mock(HostEndpointResolver.class), sender,
                new LogStreamProperties(200, 500, 8192, 1000, Duration.ofSeconds(5)), Clock.fixed(shipirNow, ZoneOffset.UTC),
                mock(StreamViewers.class));
        // the daemon log: a line written 1s after the old stream ended, stamped by the daemon clock
        Instant daemonStamp = shipirNow.plusSeconds(1).minus(skew);
        when(hosts.findById(any())).thenReturn(Optional.of(mock(DockerHost.class)));
        when(logs.follow(any(), any(), any(), anyInt(), anyBoolean(), any(), any())).thenAnswer(inv -> {
            lineSinks.add(inv.getArgument(5));
            endSinks.add(inv.getArgument(6));
            return mock(Closeable.class);
        });
        when(logs.follow(any(), any(), any(), anyInt(), any(Instant.class), anyBoolean(), any(), any())).thenAnswer(inv -> {
            Instant since = inv.getArgument(4);
            Consumer<LogLine> sink = inv.getArgument(6);
            lineSinks.add(sink);
            endSinks.add(inv.getArgument(7));
            if (!daemonStamp.isBefore(since)) { // what the daemon would replay
                sink.accept(new LogLine(LogLine.Stream.STDOUT, "after end", null));
            }
            return mock(Closeable.class);
        });
        var target = new LogStreamHandler.Target(UUID.randomUUID(), "web");
        var options = new LogStreamHandler.Options(100, false);

        skewed.subscribe(target, options, "alice", "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> endSinks.size() == 1);
        lineSinks.get(0).accept(new LogLine(LogLine.Stream.STDOUT, "old", null));
        endSinks.get(0).accept(null);
        skewed.subscribe(target, options, "bob", "s2", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> lineSinks.size() == 2);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(
                () -> assertThat(linesTo("s1", "a")).contains("after end"));
    }

    /** With timestamps on, since is the last delivered line's Docker timestamp: no Shipir clock involved at all. */
    @Test
    void sinceIsTheLastDeliveredDockerTimestampWhenTimestampsAreOn() throws Exception {
        Instant shipirNow = Instant.parse("2026-11-13T12:00:00Z");
        Instant daemonStamp = shipirNow.minus(Duration.ofHours(2)); // daemon clock hopelessly off
        var skewed = new LogStreamHandler(logs, hosts, mock(HostEndpointResolver.class), sender,
                new LogStreamProperties(200, 500, 8192, 1000, Duration.ofSeconds(5)), Clock.fixed(shipirNow, ZoneOffset.UTC),
                mock(StreamViewers.class));
        List<Integer> tails = new CopyOnWriteArrayList<>();
        List<Instant> sinces = new CopyOnWriteArrayList<>();
        mockFollows(tails, sinces);
        var target = new LogStreamHandler.Target(UUID.randomUUID(), "web");
        var options = new LogStreamHandler.Options(100, true);

        skewed.subscribe(target, options, "alice", "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> endSinks.size() == 1);
        lineSinks.get(0).accept(new LogLine(LogLine.Stream.STDOUT, "old", daemonStamp));
        endSinks.get(0).accept(null);
        skewed.subscribe(target, options, "bob", "s2", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> lineSinks.size() == 2);

        assertThat(sinces.get(1)).isEqualTo(daemonStamp);
    }

    /** Flapping container: the second stream ends before any line, the third still starts from the daemon timestamp. */
    @Test
    void sinceStaysTheDaemonTimestampWhenTheCarriedOverStreamEndsWithoutALine() throws Exception {
        Instant shipirNow = Instant.parse("2026-11-13T12:00:00Z");
        Instant daemonStamp = shipirNow.minus(Duration.ofHours(2));
        var skewed = new LogStreamHandler(logs, hosts, mock(HostEndpointResolver.class), sender,
                new LogStreamProperties(200, 500, 8192, 1000, Duration.ofSeconds(5)), Clock.fixed(shipirNow, ZoneOffset.UTC),
                mock(StreamViewers.class));
        List<Integer> tails = new CopyOnWriteArrayList<>();
        List<Instant> sinces = new CopyOnWriteArrayList<>();
        mockFollows(tails, sinces);
        var target = new LogStreamHandler.Target(UUID.randomUUID(), "web");
        var options = new LogStreamHandler.Options(100, true);

        skewed.subscribe(target, options, "alice", "s1", "a");
        await().atMost(5, TimeUnit.SECONDS).until(() -> endSinks.size() == 1);
        lineSinks.get(0).accept(new LogLine(LogLine.Stream.STDOUT, "old", daemonStamp));
        endSinks.get(0).accept(null);
        skewed.subscribe(target, options, "bob", "s2", "b");
        await().atMost(5, TimeUnit.SECONDS).until(() -> endSinks.size() == 2);
        endSinks.get(1).accept(null); // ends again, no line delivered
        skewed.subscribe(target, options, "carol", "s3", "c");
        await().atMost(5, TimeUnit.SECONDS).until(() -> endSinks.size() == 3);

        assertThat(sinces.get(1)).isEqualTo(daemonStamp);
        assertThat(sinces.get(2)).isEqualTo(daemonStamp);
    }
}
