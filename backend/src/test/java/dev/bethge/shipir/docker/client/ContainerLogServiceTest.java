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
}
