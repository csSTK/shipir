package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.LogLine;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class ContainerLogServiceImpl implements ContainerLogService {

    private final DockerCallSupport calls;

    public ContainerLogServiceImpl(DockerHostConnectionManager manager) {
        this.calls = new DockerCallSupport(manager);
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
        LineCallback callback = new LineCallback(timestamps, onLine, onEnd);
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
        LineCallback callback = new LineCallback(timestamps, onLine, onEnd);
        return calls.call(hostId, endpoint, client -> {
            var cmd = client.logContainerCmd(containerId).withStdOut(true).withStdErr(true)
                    .withFollowStream(false).withTimestamps(timestamps);
            if (since != null) {
                cmd = cmd.withSince((int) Math.min(Integer.MAX_VALUE, Math.max(0, since.getEpochSecond())));
            }
            return (tail < 0 ? cmd.withTailAll() : cmd.withTail(tail)).exec(callback);
        });
    }

    /** Reassembles lines from frames (a frame may hold part of a line or several) per stream. */
    static final class LineCallback extends ResultCallback.Adapter<Frame> {

        private final boolean timestamps;
        private final Consumer<LogLine> onLine;
        private final Consumer<Throwable> onEnd;
        private final StringBuilder stdout = new StringBuilder();
        private final StringBuilder stderr = new StringBuilder();
        private final AtomicBoolean ended = new AtomicBoolean();

        LineCallback(boolean timestamps, Consumer<LogLine> onLine, Consumer<Throwable> onEnd) {
            this.timestamps = timestamps;
            this.onLine = onLine;
            this.onEnd = onEnd;
        }

        @Override
        public synchronized void onNext(Frame frame) {
            boolean err = frame.getStreamType() == StreamType.STDERR;
            StringBuilder buffer = err ? stderr : stdout; // RAW (tty) counts as stdout
            buffer.append(new String(frame.getPayload(), java.nio.charset.StandardCharsets.UTF_8));
            int nl;
            while ((nl = buffer.indexOf("\n")) >= 0) {
                emit(err, buffer.substring(0, nl));
                buffer.delete(0, nl + 1);
            }
        }

        private void emit(boolean err, String raw) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            Instant ts = null;
            if (timestamps) {
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
                if (stdout.length() > 0) emit(false, stdout.toString());
                if (stderr.length() > 0) emit(true, stderr.toString());
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
    }
}
