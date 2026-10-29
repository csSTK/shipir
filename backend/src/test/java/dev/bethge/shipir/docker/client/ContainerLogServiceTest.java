package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.LogLine;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Frame reassembly and error mapping with a stubbed docker-java client. */
class ContainerLogServiceTest {

    private static Frame frame(StreamType type, String text) {
        return new Frame(type, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void reassemblesLinesAcrossFramesPerStreamAndFlushesRemainder() {
        List<LogLine> lines = new ArrayList<>();
        var cb = new ContainerLogServiceImpl.LineCallback(false, lines::add, e -> { });

        cb.onNext(frame(StreamType.STDOUT, "he"));
        cb.onNext(frame(StreamType.STDERR, "oops\r\n"));
        cb.onNext(frame(StreamType.STDOUT, "llo\nsecond\nrest"));
        cb.onComplete();

        assertThat(lines).extracting(LogLine::stream, LogLine::text).containsExactly(
                org.assertj.core.groups.Tuple.tuple(LogLine.Stream.STDERR, "oops"),
                org.assertj.core.groups.Tuple.tuple(LogLine.Stream.STDOUT, "hello"),
                org.assertj.core.groups.Tuple.tuple(LogLine.Stream.STDOUT, "second"),
                org.assertj.core.groups.Tuple.tuple(LogLine.Stream.STDOUT, "rest"));
    }

    @Test
    void ttyRawFramesAreStdoutAndTimestampsAreParsed() {
        List<LogLine> lines = new ArrayList<>();
        var cb = new ContainerLogServiceImpl.LineCallback(true, lines::add, e -> { });

        cb.onNext(frame(StreamType.RAW, "2026-10-23T10:00:00.123456789Z hi there\r\n"));
        cb.onNext(frame(StreamType.RAW, "no-timestamp-here\n"));

        assertThat(lines.get(0)).isEqualTo(new LogLine(LogLine.Stream.STDOUT, "hi there",
                Instant.parse("2026-10-23T10:00:00.123456789Z")));
        assertThat(lines.get(1).timestamp()).isNull();
    }

    @Test
    void endCallbackRunsOnce() {
        List<Throwable> ends = new ArrayList<>();
        var cb = new ContainerLogServiceImpl.LineCallback(false, l -> { }, ends::add);
        cb.onComplete();
        cb.onError(new RuntimeException("late"));
        assertThat(ends).hasSize(1);
    }

    @Test
    void streamFailureIsReportedAsErrorNotAsNormalEnd() {
        List<Throwable> ends = new ArrayList<>();
        var cb = new ContainerLogServiceImpl.LineCallback(false, l -> { }, ends::add);
        RuntimeException failure = new RuntimeException("broken");
        cb.onError(failure);
        assertThat(ends).containsExactly(failure);
    }

    @Test
    void unknownContainerIsNotFoundWithoutOpeningStream() {
        DockerHostConnectionManager manager = mock(DockerHostConnectionManager.class);
        DockerClient client = mock(DockerClient.class, Answers.RETURNS_DEEP_STUBS);
        when(manager.readTimeout()).thenReturn(Duration.ofSeconds(1));
        when(manager.client(any(), any())).thenReturn(client);
        when(client.inspectContainerCmd("gone").exec()).thenThrow(new NotFoundException("x"));
        var service = new ContainerLogServiceImpl(manager);

        assertThatThrownBy(() -> service.follow(UUID.randomUUID(), new DockerEndpoint.Local("unix:///x.sock"), "gone", 10, false, l -> { }))
                .isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }

    @Test
    void snapshotReadsWithoutFollowAndPassesSinceAndTail() {
        DockerHostConnectionManager manager = mock(DockerHostConnectionManager.class);
        DockerClient client = mock(DockerClient.class, Answers.RETURNS_DEEP_STUBS);
        when(manager.readTimeout()).thenReturn(Duration.ofSeconds(1));
        when(manager.client(any(), any())).thenReturn(client);
        var cmd = mock(com.github.dockerjava.api.command.LogContainerCmd.class, Answers.RETURNS_SELF);
        when(client.logContainerCmd("web")).thenReturn(cmd);
        var service = new ContainerLogServiceImpl(manager);

        service.snapshot(UUID.randomUUID(), new DockerEndpoint.Local("unix:///x.sock"), "web", 50,
                Instant.ofEpochSecond(1_700_000_000L), true, l -> { }, e -> { });

        org.mockito.Mockito.verify(cmd).withFollowStream(false);
        org.mockito.Mockito.verify(cmd).withSince(1_700_000_000);
        org.mockito.Mockito.verify(cmd).withTail(50);
        org.mockito.Mockito.verify(cmd).withTimestamps(true);
        org.mockito.Mockito.verify(cmd, org.mockito.Mockito.never()).withFollowStream(true);
    }

    @Test
    void newlineFreeStreamIsSplitAtTheCapWithBoundedBufferAndNoContentLost() {
        List<LogLine> lines = new ArrayList<>();
        var cb = new ContainerLogServiceImpl.LineCallback(false, lines::add, e -> { }, 100);
        String chunk = "x".repeat(1000);
        int total = 0;
        for (int i = 0; i < 2000; i++) { // 2 MB without a single newline
            cb.onNext(frame(StreamType.STDOUT, chunk));
            total += chunk.length();
            assertThat(cb.bufferedChars()).isLessThanOrEqualTo(100);
        }
        cb.onComplete();

        assertThat(lines).allSatisfy(l -> assertThat(l.text().length()).isLessThanOrEqualTo(100));
        assertThat(lines.stream().mapToInt(l -> l.text().length()).sum()).isEqualTo(total);
    }

    @Test
    void overlongLineFollowedByNewlineKeepsFollowingLinesIntact() {
        List<LogLine> lines = new ArrayList<>();
        var cb = new ContainerLogServiceImpl.LineCallback(false, lines::add, e -> { }, 10);
        cb.onNext(frame(StreamType.STDOUT, "a".repeat(25) + "\r\nnext\n" + "b".repeat(10) + "\r\nend\n"));

        assertThat(lines).extracting(LogLine::text).containsExactly(
                "a".repeat(10), "a".repeat(10), "aaaaa", "next", "b".repeat(10), "end");
    }

    @Test
    void splitNeverCutsASurrogatePair() {
        List<LogLine> lines = new ArrayList<>();
        var cb = new ContainerLogServiceImpl.LineCallback(false, lines::add, e -> { }, 5);
        cb.onNext(frame(StreamType.STDOUT, "abcd\uD83D\uDE00efgh\n")); // emoji straddles the cap
        assertThat(lines).extracting(LogLine::text).containsExactly("abcd", "\uD83D\uDE00efg", "h");
    }

    @Test
    void continuationChunkIsNotParsedForATimestamp() {
        List<LogLine> lines = new ArrayList<>();
        var cb = new ContainerLogServiceImpl.LineCallback(true, lines::add, e -> { }, 60);
        String ts = "2026-10-23T10:00:00.123456789Z";
        cb.onNext(frame(StreamType.STDOUT, ts + " " + "z".repeat(40) + "\n"));
        assertThat(lines.get(0).timestamp()).isEqualTo(Instant.parse(ts));
        assertThat(lines.get(0).text()).isEqualTo("z".repeat(29));
        assertThat(lines.get(1).timestamp()).isNull();
        assertThat(lines.get(1).text()).isEqualTo("z".repeat(11));
    }

    @Test
    void multiByteCharactersSplitAcrossFramesAreDecodedCorrectly() {
        List<LogLine> lines = new ArrayList<>();
        var cb = new ContainerLogServiceImpl.LineCallback(false, lines::add, e -> { });
        byte[] bytes = "gr\u00FC\u00DFe \u65E5\u672C \uD83D\uDE00\n".getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) { // one byte per frame
            cb.onNext(new Frame(StreamType.STDOUT, new byte[]{b}));
        }
        assertThat(lines).extracting(LogLine::text).containsExactly("gr\u00FC\u00DFe \u65E5\u672C \uD83D\uDE00");
    }

    @Test
    void crlfStraddlingTheCapAcrossFramesYieldsOneLine() {
        List<LogLine> lines = new ArrayList<>();
        var cb = new ContainerLogServiceImpl.LineCallback(false, lines::add, e -> { }, 10);
        cb.onNext(frame(StreamType.STDOUT, "a".repeat(10) + "\r"));
        cb.onNext(frame(StreamType.STDOUT, "\n"));
        cb.onComplete();
        assertThat(lines).extracting(LogLine::text).containsExactly("a".repeat(10));
    }

    @Test
    void trailingCarriageReturnAtEndOfStreamIsStripped() {
        List<LogLine> lines = new ArrayList<>();
        var cb = new ContainerLogServiceImpl.LineCallback(false, lines::add, e -> { });
        cb.onNext(frame(StreamType.STDOUT, "abc\r"));
        cb.onComplete();
        assertThat(lines).extracting(LogLine::text).containsExactly("abc");
    }

    @Test
    void loneCarriageReturnLeftAfterASplitIsNotItsOwnLineAtEndOfStream() {
        List<LogLine> lines = new ArrayList<>();
        var cb = new ContainerLogServiceImpl.LineCallback(false, lines::add, e -> { }, 10);
        cb.onNext(frame(StreamType.STDOUT, "a".repeat(11) + "\r"));
        cb.onComplete();
        assertThat(lines).extracting(LogLine::text).containsExactly("a".repeat(10), "a");

        lines.clear();
        var cb2 = new ContainerLogServiceImpl.LineCallback(false, lines::add, e -> { }, 10);
        cb2.onNext(frame(StreamType.STDOUT, "a".repeat(10) + "b"));
        cb2.onNext(frame(StreamType.STDOUT, "\r"));
        cb2.onComplete();
        assertThat(lines).extracting(LogLine::text).containsExactly("a".repeat(10), "b");
    }

    @Test
    void danglingPartialUtf8SequenceAtEndBecomesOneReplacementCharacter() {
        List<LogLine> lines = new ArrayList<>();
        var cb = new ContainerLogServiceImpl.LineCallback(false, lines::add, e -> { });
        cb.onNext(new Frame(StreamType.STDOUT, new byte[]{'o', 'k', (byte) 0xE6, (byte) 0x97}));
        cb.onComplete();
        assertThat(lines).extracting(LogLine::text).containsExactly("ok\uFFFD");
    }

    @Test
    void invalidUtf8NeverGrowsTheBufferPastTheCap() {
        List<LogLine> lines = new ArrayList<>();
        var cb = new ContainerLogServiceImpl.LineCallback(false, lines::add, e -> { }, 50);
        byte[] junk = new byte[1000];
        java.util.Arrays.fill(junk, (byte) 0xFF);
        for (int i = 0; i < 500; i++) {
            cb.onNext(new Frame(StreamType.STDOUT, junk));
            assertThat(cb.bufferedChars()).isLessThanOrEqualTo(50);
        }
        cb.onComplete();
        assertThat(lines).allSatisfy(l -> assertThat(l.text().length()).isLessThanOrEqualTo(50));
    }
}
