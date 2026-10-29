package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.ContainerLogService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.LogLine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import dev.bethge.shipir.permission.CurrentAccess;
import dev.bethge.shipir.permission.PermissionSubject;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Container log download: a finite, streamed snapshot (never a follow). Who may download is decided by
 * {@link HostResourcesService#container}: the same visibility as the container details (404 for containers the user
 * may not see, also on hosts visible only through deployment grants). Memory is bounded by a small hand-over queue
 * between the Docker I/O thread and the response; the output is capped in bytes and in time.
 */
@Service
@EnableConfigurationProperties(LogDownloadProperties.class)
public class ContainerLogDownloadService {

    /** Largest {@code tail} accepted as a number; {@code all} has no line limit but still the byte and time caps. */
    static final int MAX_TAIL = 1_000_000;

    private static final Pattern EPOCH_SECONDS = Pattern.compile("\\d{1,12}");
    private static final Pattern TAIL = Pattern.compile("[1-9]\\d{0,6}");
    private static final int QUEUE_CAPACITY = 256;
    private static final String TRUNCATED_SIZE = "[output truncated: size limit reached]\n";
    private static final String TRUNCATED_TIME = "[output truncated: time limit reached]\n";
    private static final String FAILED = "[log stream failed]\n";

    /**
     * The response body and the file name to offer. {@code release} frees the download permit taken by {@link #open};
     * it is idempotent and also called by the body itself, so a caller that fails between {@code open} and writing
     * the body (headers, output stream) can call it without double-releasing.
     */
    public record Download(String fileName, StreamingResponseBody body, Runnable release) {
    }

    /** Extra time after the download deadline before a write that is still blocked is aborted. */
    static final long WRITE_GRACE_MILLIS = 1000;

    /**
     * Watchdog for writes stalled in the client socket: a blocking {@code out.write} cannot notice the deadline, so
     * a timer bounds what it can. The Docker read is closed first and always (that bound is guaranteed). Closing the
     * response stream is best effort: it may itself block on the stalled socket, so it runs on its own short-lived
     * thread and never holds up the shared watchdog thread; Tomcat's own socket write timeout is the real backstop
     * for the response side. Interrupting the request thread would not help, blocking socket writes are not
     * interruptible.
     */
    private static final ScheduledExecutorService WATCHDOG = newWatchdog();

    private static ScheduledExecutorService newWatchdog() {
        var executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "log-download-watchdog");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private record End(Throwable failure) {
    }

    private final HostResourcesService resources;
    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final ContainerLogService logs;
    private final LogDownloadProperties properties;
    private final CurrentAccess access;
    private final Semaphore slots;
    private final ConcurrentHashMap<UUID, Integer> perUser = new ConcurrentHashMap<>();

    ContainerLogDownloadService(HostResourcesService resources, DockerHostRepository hosts,
                                HostEndpointResolver endpoints, ContainerLogService logs,
                                LogDownloadProperties properties, CurrentAccess access) {
        this.access = access;
        this.slots = new Semaphore(properties.maxConcurrent());
        this.resources = resources;
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.logs = logs;
        this.properties = properties;
    }

    /**
     * Validates the parameters, checks visibility and returns the streaming body; nothing is read from Docker before
     * the body is written. {@code since}: ISO-8601 instant or epoch seconds; {@code tail}: 1..{@value #MAX_TAIL} or
     * {@code all} (default); {@code timestamps}: true (default) or false.
     */
    public Download open(UUID hostId, String containerId, String since, String tail, String timestamps) {
        Instant sinceInstant = parseSince(since);
        int tailLines = parseTail(tail);
        boolean withTimestamps = parseTimestamps(timestamps);
        UUID user = access.subject().map(PermissionSubject::userId).orElse(new UUID(0, 0));
        acquire(user);
        try {
            ContainerDetailsDto container = resources.container(hostId, containerId);
            DockerEndpoint endpoint = endpointOf(hostId);
            String fileName = fileName(container.name());
            long maxBytes = properties.maxBytes().toBytes();
            long timeoutNanos = properties.timeout().toNanos();
            AtomicBoolean released = new AtomicBoolean();
            Runnable releasePermit = () -> {
                if (released.compareAndSet(false, true)) {
                    release(user);
                }
            };
            StreamingResponseBody body = out -> {
                try {
                    write(out, hostId, endpoint, container.id(), tailLines, sinceInstant, withTimestamps, maxBytes,
                            timeoutNanos);
                } finally {
                    releasePermit.run();
                }
            };
            return new Download(fileName, body, releasePermit);
        } catch (RuntimeException e) {
            release(user);
            throw e;
        }
    }

    /**
     * A download holds a request thread for up to the timeout, so the number running at once is bounded, globally
     * and per user. No waiting: when exhausted the request is answered with 429 before any Docker call. The permit is
     * released when the body finishes (or when {@link #open} fails); a body must therefore be written exactly once.
     */
    private void acquire(UUID user) {
        boolean[] userOk = {false};
        perUser.compute(user, (k, n) -> {
            int current = n == null ? 0 : n;
            if (current >= properties.maxPerUser()) {
                return n;
            }
            userOk[0] = true;
            return current + 1;
        });
        if (!userOk[0]) {
            throw tooMany();
        }
        if (!slots.tryAcquire()) {
            release(user, false);
            throw tooMany();
        }
    }

    private void release(UUID user) {
        release(user, true);
    }

    private void release(UUID user, boolean slot) {
        if (slot) {
            slots.release();
        }
        perUser.compute(user, (k, n) -> n == null || n <= 1 ? null : n - 1);
    }

    private static HostException tooMany() {
        return new HostException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_DOWNLOADS",
                "Too many log downloads are running, try again shortly");
    }

    private void write(OutputStream out, UUID hostId, DockerEndpoint endpoint, String containerId, int tail,
                       Instant since, boolean timestamps, long maxBytes, long timeoutNanos) throws IOException {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Closeable> handle = new AtomicReference<>();
        // the producer runs on its own thread so a log service that delivers inline cannot block on the full queue
        Thread.ofVirtual().name("log-download").start(() -> {
            try {
                Closeable c = logs.snapshot(hostId, endpoint, containerId, tail, since, timestamps,
                        line -> offer(queue, line, cancelled), failure -> offer(queue, new End(failure), cancelled));
                handle.set(c);
                if (cancelled.get()) {
                    closeQuietly(c);
                }
            } catch (RuntimeException e) {
                offer(queue, new End(e), cancelled);
            }
        });
        long deadline = System.nanoTime() + timeoutNanos;
        long written = 0;
        AtomicBoolean done = new AtomicBoolean();
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> abortStalled(done, cancelled, out, handle.get()), timeoutNanos + TimeUnit.MILLISECONDS.toNanos(WRITE_GRACE_MILLIS), TimeUnit.NANOSECONDS);
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    out.write(TRUNCATED_TIME.getBytes(StandardCharsets.UTF_8));
                    break;
                }
                Object item = queue.poll(remaining, TimeUnit.NANOSECONDS);
                if (item == null) {
                    continue;
                }
                if (item instanceof End end) {
                    if (end.failure() != null) {
                        out.write(FAILED.getBytes(StandardCharsets.UTF_8));
                    }
                    break;
                }
                byte[] bytes = format((LogLine) item, timestamps).getBytes(StandardCharsets.UTF_8);
                if (written + bytes.length > maxBytes) {
                    out.write(TRUNCATED_SIZE.getBytes(StandardCharsets.UTF_8));
                    break;
                }
                out.write(bytes);
                written += bytes.length;
            }
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            done.set(true); // before the permit is released and the response completes: the watchdog must not touch out later
            watchdog.cancel(false);
            cancelled.set(true);
            closeQuietly(handle.get());
        }
    }

    /**
     * Watchdog action. The Docker handle is always closed; the response stream only while the download is not done
     * (cancel does not wait for a task already running, and the stream may belong to a finished request by now).
     */
    static void abortStalled(AtomicBoolean done, AtomicBoolean cancelled, OutputStream out, Closeable docker) {
        cancelled.set(true);
        closeQuietly(docker);
        if (!done.get()) {
            Thread.ofVirtual().name("log-download-abort").start(() -> {
                if (!done.get()) {
                    closeQuietly(out);
                }
            });
        }
    }

    /** Hand-over with back pressure; gives up once the consumer is gone. */
    private static void offer(BlockingQueue<Object> queue, Object item, AtomicBoolean cancelled) {
        try {
            while (!cancelled.get()) {
                if (queue.offer(item, 100, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String format(LogLine line, boolean timestamps) {
        String prefix = timestamps && line.timestamp() != null ? line.timestamp() + " " : "";
        return prefix + line.text() + "\n";
    }

    static Instant parseSince(String since) {
        if (since == null) {
            return null;
        }
        try {
            if (EPOCH_SECONDS.matcher(since).matches()) {
                return Instant.ofEpochSecond(Long.parseLong(since));
            }
            if (since.length() <= 40) {
                return Instant.parse(since);
            }
        } catch (DateTimeParseException | ArithmeticException e) {
            // falls through to the error below
        }
        throw invalid("since must be an ISO-8601 instant or epoch seconds");
    }

    static int parseTail(String tail) {
        if (tail == null || tail.equals("all")) {
            return -1;
        }
        if (TAIL.matcher(tail).matches()) {
            int lines = Integer.parseInt(tail);
            if (lines <= MAX_TAIL) {
                return lines;
            }
        }
        throw invalid("tail must be a number from 1 to " + MAX_TAIL + " or 'all'");
    }

    private static boolean parseTimestamps(String value) {
        if (value == null || value.equals("true")) {
            return true;
        }
        if (value.equals("false")) {
            return false;
        }
        throw invalid("timestamps must be true or false");
    }

    /** {@code <name>-logs.txt}, name reduced to ASCII letters, digits, dot, dash and underscore. */
    static String fileName(String containerName) {
        String safe = containerName == null ? "" : containerName.replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("\\.{2,}", "_").replaceFirst("^[._-]+", "");
        if (safe.length() > 64) {
            safe = safe.substring(0, 64);
        }
        return (safe.isEmpty() ? "container" : safe) + "-logs.txt";
    }

    private DockerEndpoint endpointOf(UUID hostId) {
        DockerHost host = hosts.findById(hostId).orElseThrow(
                () -> new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found"));
        try {
            return endpoints.endpointOf(host);
        } catch (EncryptionKeyMissingException e) {
            throw new HostException(HttpStatus.BAD_GATEWAY, "ENCRYPTION_KEY_MISSING", "No encryption key is configured");
        } catch (SecretDecryptionException e) {
            throw new HostException(HttpStatus.BAD_GATEWAY, "DECRYPTION_FAILED",
                    "Stored connection secrets could not be decrypted");
        }
    }

    private static HostException invalid(String message) {
        return new HostException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message);
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException | RuntimeException e) {
            // best effort, the read is over either way
        }
    }
}
