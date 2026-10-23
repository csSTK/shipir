package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class ExecServiceImpl implements ExecService {

    private static final Duration CLOSE_GRACE = Duration.ofSeconds(2);

    private final DockerCallSupport calls;

    public ExecServiceImpl(DockerHostConnectionManager manager) {
        this.calls = new DockerCallSupport(manager);
    }

    @Override
    public ExecHandle open(UUID hostId, DockerEndpoint endpoint, String containerId, List<String> cmd, int cols,
                           int rows, Consumer<byte[]> output) {
        return open(hostId, endpoint, containerId, cmd, cols, rows, output, error -> { });
    }

    @Override
    public ExecHandle open(UUID hostId, DockerEndpoint endpoint, String containerId, List<String> cmd, int cols,
                           int rows, Consumer<byte[]> output, Consumer<Throwable> onEnd) {
        // exec on a stopped container only fails with a generic conflict, so check the state ourselves
        boolean running = calls.call(hostId, endpoint,
                client -> Boolean.TRUE.equals(client.inspectContainerCmd(containerId).exec().getState().getRunning()));
        if (!running) {
            throw new DockerConnectionException(DockerErrorCode.INVALID_STATE,
                    "The container is not in a state that allows this action.", null);
        }
        StdinPipe stdin = new StdinPipe();
        SessionCallback callback = new SessionCallback(output, onEnd);
        String execId = calls.call(hostId, endpoint, client -> {
            String id = client.execCreateCmd(containerId).withAttachStdin(true).withAttachStdout(true)
                    .withAttachStderr(true).withTty(true).withCmd(cmd.toArray(String[]::new)).exec().getId();
            client.execStartCmd(id).withTty(true).withStdIn(stdin).exec(callback);
            return id;
        });
        Handle handle = new Handle(hostId, endpoint, execId, stdin, callback);
        handle.resize(cols, rows);
        return handle;
    }

    private final class Handle implements ExecHandle {

        private final UUID hostId;
        private final DockerEndpoint endpoint;
        private final String execId;
        private final StdinPipe stdin;
        private final SessionCallback callback;
        private final AtomicBoolean closed = new AtomicBoolean();

        Handle(UUID hostId, DockerEndpoint endpoint, String execId, StdinPipe stdin, SessionCallback callback) {
            this.hostId = hostId;
            this.endpoint = endpoint;
            this.execId = execId;
            this.stdin = stdin;
            this.callback = callback;
        }

        @Override
        public void write(byte[] bytes) {
            if (!closed.get()) {
                stdin.offer(bytes.clone());
            }
        }

        @Override
        public void resize(int cols, int rows) {
            if (closed.get() || cols <= 0 || rows <= 0) {
                return;
            }
            try {
                calls.call(hostId, endpoint, client -> client.resizeExecCmd(execId).withSize(rows, cols).exec());
            } catch (RuntimeException ignored) {
                // cosmetic, e.g. the process already exited
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                // Docker keeps the exec process alive when the connection drops, so ask it to leave through the
                // TTY (Ctrl-C interrupts the foreground job, Ctrl-D ends a shell), then drop the connection.
                stdin.offer(new byte[]{0x03, 0x04});
                stdin.finish();
                try {
                    callback.awaitCompletion(CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    callback.close();
                } catch (IOException ignored) {
                    // already gone
                }
            }
        }
    }

    /** Blocking stdin fed by {@code write}; unlike PipedInputStream it does not depend on the writer thread staying alive. */
    private static final class StdinPipe extends InputStream {

        private static final byte[] EOF = new byte[0];

        private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private byte[] current = new byte[0];
        private int pos;
        private boolean eof;

        void offer(byte[] bytes) {
            if (bytes.length > 0) queue.add(bytes);
        }

        void finish() {
            queue.add(EOF);
        }

        private boolean fill() throws IOException {
            while (!eof && pos >= current.length) {
                try {
                    current = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
                pos = 0;
                eof = current == EOF;
            }
            return !eof;
        }

        @Override
        public int read() throws IOException {
            return fill() ? current[pos++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!fill()) return -1;
            int n = Math.min(len, current.length - pos);
            System.arraycopy(current, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    private static final class SessionCallback extends ResultCallback.Adapter<Frame> {

        private final Consumer<byte[]> output;
        private final Consumer<Throwable> onEnd;
        private final AtomicBoolean ended = new AtomicBoolean();

        SessionCallback(Consumer<byte[]> output, Consumer<Throwable> onEnd) {
            this.output = output;
            this.onEnd = onEnd;
        }

        @Override
        public void onNext(Frame frame) {
            output.accept(frame.getPayload());
        }

        private void finish(Throwable error) {
            if (ended.compareAndSet(false, true)) {
                onEnd.accept(error);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            super.onError(throwable);
            finish(throwable);
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
