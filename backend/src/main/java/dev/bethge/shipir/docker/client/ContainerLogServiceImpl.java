package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.LogLine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class ContainerLogServiceImpl implements ContainerLogService {

    private final DockerCallSupport calls;
    private final int maxLineLength;

    @Autowired
    public ContainerLogServiceImpl(DockerHostConnectionManager manager,
                                   @Value("${shipir.docker.logs.max-line-length:" + DEFAULT_MAX_LINE_LENGTH + "}")
                                   int maxLineLength) {
        this.calls = new DockerCallSupport(manager);
        this.maxLineLength = maxLineLength;
    }

    ContainerLogServiceImpl(DockerHostConnectionManager manager) {
        this(manager, DEFAULT_MAX_LINE_LENGTH);
    }

    @Override
    public Closeable follow(UUID hostId, DockerEndpoint endpoint, String containerId, int tail, boolean timestamps,
                            Consumer<LogLine> onLine) {
        return follow(hostId, endpoint, containerId, tail, timestamps, onLine, error -> { });
    }

    @Override
    public Closeable follow(UUID hostId, DockerEndpoint endpoint, String containerId, int tail, boolean timestamps,
                            Consumer<LogLine> onLine, Consumer<Throwable> onEnd) {
        // the log call itself is asynchronous and would hide an unknown id, so check it first
        calls.call(hostId, endpoint, client -> client.inspectContainerCmd(containerId).exec());
        LineCallback callback = new LineCallback(timestamps, onLine, onEnd, maxLineLength);
        return calls.call(hostId, endpoint, client -> {
            var cmd = client.logContainerCmd(containerId).withStdOut(true).withStdErr(true)
                    .withFollowStream(true).withTimestamps(timestamps);
            return (tail < 0 ? cmd.withTailAll() : cmd.withTail(tail)).exec(callback);
        });
    }

    @Override
    public Closeable snapshot(UUID hostId, DockerEndpoint endpoint, String containerId, int tail, Instant since,
                              boolean timestamps, Consumer<LogLine> onLine, Consumer<Throwable> onEnd) {
        calls.call(hostId, endpoint, client -> client.inspectContainerCmd(containerId).exec());
        LineCallback callback = new LineCallback(timestamps, onLine, onEnd, maxLineLength);
        return calls.call(hostId, endpoint, client -> {
            var cmd = client.logContainerCmd(containerId).withStdOut(true).withStdErr(true)
                    .withFollowStream(false).withTimestamps(timestamps);
            if (since != null) {
                cmd = cmd.withSince((int) Math.min(Integer.MAX_VALUE, Math.max(0, since.getEpochSecond())));
            }
            return (tail < 0 ? cmd.withTailAll() : cmd.withTail(tail)).exec(callback);
        });
    }

    /** Default for {@code shipir.docker.logs.max-line-length}, in chars. */
    static final int DEFAULT_MAX_LINE_LENGTH = 65_536;

    /**
     * Reassembles lines from frames (a frame may hold part of a line or several) per stream.
     *
     * <p>Memory is bounded: a line longer than {@code maxLineLength} chars is <em>split</em> into consecutive
     * lines of at most that length (never inside a surrogate pair), so a container that writes without newlines
     * can't grow the buffer without limit. Splitting rather than truncating keeps the content for downloads; the
     * streaming layer applies its own display cap (and "[truncated]" marker) on top. Bytes are decoded with a
     * stateful UTF-8 decoder so a multi-byte character split across frames survives.
     */
    static final class LineCallback extends ResultCallback.Adapter<Frame> {

        private final boolean timestamps;
        private final Consumer<LogLine> onLine;
        private final Consumer<Throwable> onEnd;
        private final int maxLineLength;
        private final PartialLine stdout = new PartialLine();
        private final PartialLine stderr = new PartialLine();
        private final AtomicBoolean ended = new AtomicBoolean();

        LineCallback(boolean timestamps, Consumer<LogLine> onLine, Consumer<Throwable> onEnd) {
            this(timestamps, onLine, onEnd, DEFAULT_MAX_LINE_LENGTH);
        }

        LineCallback(boolean timestamps, Consumer<LogLine> onLine, Consumer<Throwable> onEnd, int maxLineLength) {
            this.timestamps = timestamps;
            this.onLine = onLine;
            this.onEnd = onEnd;
            this.maxLineLength = Math.max(2, maxLineLength);
        }

        /** Chars currently held back (for tests). */
        synchronized int bufferedChars() {
            return Math.max(stdout.text.length(), stderr.text.length());
        }

        @Override
        public synchronized void onNext(Frame frame) {
            boolean err = frame.getStreamType() == StreamType.STDERR;
            PartialLine part = err ? stderr : stdout; // RAW (tty) counts as stdout
            part.append(frame.getPayload());
            drain(err, part, false);
        }

        private void drain(boolean err, PartialLine part, boolean atEnd) {
            StringBuilder buf = part.text;
            while (true) {
                int nl = buf.indexOf("\n");
                int end = nl >= 0 ? nl : buf.length();
                // a CR directly before the LF, or a trailing CR whose LF may arrive in the next frame, is not
                // line content: it doesn't count toward the cap
                if (end > 0 && buf.charAt(end - 1) == '\r') end--;
                if (end > maxLineLength) {
                    int cut = maxLineLength;
                    if (Character.isHighSurrogate(buf.charAt(cut - 1))) cut--;
                    emit(err, buf.substring(0, cut), part.continuation);
                    buf.delete(0, cut);
                    part.continuation = true;
                } else if (nl >= 0) {
                    emit(err, buf.substring(0, end), part.continuation);
                    buf.delete(0, nl + 1);
                    part.continuation = false;
                } else {
                    if (atEnd && buf.length() > 0) {
                        String rest = buf.toString();
                        buf.setLength(0);
                        if (rest.endsWith("\r")) rest = rest.substring(0, rest.length() - 1);
                        if (!rest.isEmpty() || !part.continuation) emit(err, rest, part.continuation);
                    }
                    return;
                }
            }
        }

        private void emit(boolean err, String line, boolean continuation) {
            Instant ts = null;
            if (timestamps && !continuation) {
                int space = line.indexOf(' ');
                if (space > 0) {
                    try {
                        ts = Instant.parse(line.substring(0, space));
                        line = line.substring(space + 1);
                    } catch (java.time.format.DateTimeParseException ignored) {
                        // keep the line as is
                    }
                }
            }
            onLine.accept(new LogLine(err ? LogLine.Stream.STDERR : LogLine.Stream.STDOUT, line, ts));
        }

        private synchronized void finish(Throwable error) {
            if (ended.compareAndSet(false, true)) {
                stdout.flushDecoder();
                stderr.flushDecoder();
                drain(false, stdout, true);
                drain(true, stderr, true);
                onEnd.accept(error);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            // report first: super.onError closes the callback, whose close() would finish(null)
            finish(throwable);
            super.onError(throwable);
        }

        @Override
        public void onComplete() {
            super.onComplete();
            finish(null);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                finish(null);
            }
        }

        /** Undelivered text of one stream plus the decoder state for a character split across frames. */
        private static final class PartialLine {
            final StringBuilder text = new StringBuilder();
            private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            private ByteBuffer pending = ByteBuffer.allocate(0);
            boolean continuation;

            void append(byte[] payload) {
                ByteBuffer in = ByteBuffer.allocate(pending.remaining() + payload.length);
                in.put(pending).put(payload).flip();
                CharBuffer out = CharBuffer.allocate(in.remaining() + 1);
                decoder.decode(in, out, false);
                out.flip();
                text.append(out);
                // an incomplete trailing sequence (at most 3 bytes) waits for the next frame; copy so the frame isn't retained
                pending = ByteBuffer.wrap(java.util.Arrays.copyOfRange(in.array(), in.arrayOffset() + in.position(),
                        in.arrayOffset() + in.limit()));
            }

            void flushDecoder() {
                if (pending.hasRemaining()) {
                    text.append('\uFFFD');
                    pending = ByteBuffer.allocate(0);
                }
            }
        }
    }
}
