package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.dto.DockerEventDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Keeps one host's {@link DockerEventService} stream open: connect, wait for the stream to end, report, wait with
 * exponential backoff, reconnect, until {@link #stop()}.
 *
 * <p>Lives in {@code docker/} and knows no feature: how the endpoint is found (a host lookup) and what is done with
 * events and status changes is up to the {@link Listener} of the caller, so the retry and threading logic exists
 * once for every consumer of the event stream. One instance per host and worker thread: {@link #run()} blocks the
 * calling thread (a virtual thread) until the loop ends. The delay starts at {@code initial}, doubles after each
 * failed round up to {@code max} and resets only once a connection proved stable: it delivered an event or stayed up for
 * at least {@code max(initial, 10s)} (a daemon or proxy that accepts and drops at once must not cause a reconnect storm). {@link #stop()} is cooperative
 * (it wakes a wait on the monitor, the thread is never interrupted); an interrupt also ends the loop.
 * Listener callbacks never run under this object's monitor.
 */
public final class ReconnectingEventStream {

    private static final Logger log = LoggerFactory.getLogger(ReconnectingEventStream.class);

    /** What the owner does at the points of the loop; everything but {@link #endpoint()} and {@link #onEvent} is optional. */
    public interface Listener {

        /** Endpoint of the host, looked up before every attempt; empty if the host no longer exists (no retry). */
        Optional<DockerEndpoint> endpoint();

        /** One Docker event; called from a Docker I/O thread, must not block for long. */
        void onEvent(DockerEventDto event);

        /** A stream was opened. */
        default void onConnected() {
        }

        /** The stream failed or ended; {@code code} is a {@link DockerErrorCode} name. Called before the backoff wait. */
        default void onDisconnected(String code) {
        }

        /** {@link #endpoint()} was empty; the loop ends right after. */
        default void onHostGone() {
        }
    }

    /** How the backoff wait is carried out; a seam so tests can record the delays instead of sleeping. */
    @FunctionalInterface
    interface Waiter {
        void await(Duration delay) throws InterruptedException;
    }

    /** A connection that lasted at least this long (or delivered an event) counts as stable. */
    static final Duration MIN_STABLE = Duration.ofSeconds(10);

    private final UUID hostId;
    private final DockerEventService events;
    private final Duration initial;
    private final Duration max;
    private final Listener listener;
    private final Waiter waiter;
    private final LongSupplier nanoClock;
    private volatile boolean eventSeen;
    private final Object lock = new Object();
    private boolean stopped;
    private Closeable current;

    public ReconnectingEventStream(UUID hostId, DockerEventService events, Duration initial, Duration max,
                                   Listener listener) {
        this(hostId, events, initial, max, listener, null);
    }

    /** Test seam: a non-null {@code waiter} replaces the real wait on the monitor. */
    ReconnectingEventStream(UUID hostId, DockerEventService events, Duration initial, Duration max,
                            Listener listener, Waiter waiter) {
        this(hostId, events, initial, max, listener, waiter, System::nanoTime);
    }

    /** Test seam: additionally replaces the clock that measures how long a connection stayed up. */
    ReconnectingEventStream(UUID hostId, DockerEventService events, Duration initial, Duration max,
                            Listener listener, Waiter waiter, LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.waiter = waiter != null ? waiter : this::awaitBackoff;
        this.hostId = hostId;
        this.events = events;
        this.initial = initial;
        this.max = max;
        this.listener = listener;
    }

    /** Ends the loop: wakes a wait, closes the open stream. Idempotent, callable from any thread. */
    public void stop() {
        Closeable toClose;
        synchronized (lock) {
            stopped = true;
            toClose = current;
            current = null;
            lock.notifyAll();
        }
        closeQuietly(toClose);
    }

    public boolean isStopped() {
        synchronized (lock) {
            return stopped;
        }
    }

    /** Runs the loop on the calling thread until stopped, interrupted or the host is gone. */
    public void run() {
        Duration delay = initial;
        try {
            while (!isStopped()) {
                CompletableFuture<Throwable> ended = new CompletableFuture<>();
                String failure;
                try {
                    Optional<DockerEndpoint> endpoint = listener.endpoint();
                    if (endpoint.isEmpty()) {
                        guarded("onHostGone", listener::onHostGone);
                        return; // host is gone, retrying is pointless
                    }
                    if (isStopped()) {
                        return; // stopped during the lookup: no stream is opened
                    }
                    Closeable handle = events.stream(hostId, endpoint.get(), this::deliver, ended::complete);
                    synchronized (lock) {
                        if (stopped) {
                            closeQuietly(handle);
                            return;
                        }
                        current = handle;
                    }
                    eventSeen = false;
                    long connectedAt = nanoClock.getAsLong();
                    listener.onConnected();
                    ended.whenComplete((end, error) -> {
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                    });
                    synchronized (lock) {
                        while (!stopped && !ended.isDone()) {
                            lock.wait();
                        }
                    }
                    if (isStopped()) {
                        return;
                    }
                    Duration stable = initial.compareTo(MIN_STABLE) > 0 ? initial : MIN_STABLE;
                    if (eventSeen || nanoClock.getAsLong() - connectedAt >= stable.toNanos()) {
                        delay = initial; // the connection proved itself, start the backoff over
                    }
                    failure = codeOf(ended.getNow(null));
                } catch (DockerConnectionException e) {
                    failure = e.getCode().name();
                } catch (RuntimeException e) {
                    // e.g. undecryptable TLS material or missing encryption key: no detail to the listener
                    log.warn("Docker event stream of host {} failed: {}", hostId, e.getClass().getSimpleName());
                    failure = "UNAVAILABLE";
                }
                closeCurrent();
                if (isStopped()) {
                    return;
                }
                String code = failure;
                guarded("onDisconnected", () -> listener.onDisconnected(code));
                waiter.await(delay);
                if (isStopped()) {
                    return;
                }
                delay = delay.multipliedBy(2).compareTo(max) > 0 ? max : delay.multipliedBy(2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeCurrent();
        }
    }

    /** Hands an event to the listener; a throwing listener must not break the Docker I/O thread. */
    private void deliver(DockerEventDto event) {
        eventSeen = true;
        guarded("onEvent", () -> listener.onEvent(event));
    }

    /** Runs a listener callback; a failure is logged (class name only) and the loop carries on and retries. */
    private void guarded(String callback, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Docker event listener {} of host {} failed: {}", callback, hostId, e.getClass().getSimpleName());
        }
    }

    /** Sleeps for the backoff delay on the monitor; {@link #stop()} wakes it early. */
    private void awaitBackoff(Duration delay) throws InterruptedException {
        long deadline = System.nanoTime() + delay.toNanos();
        synchronized (lock) {
            long remaining;
            while (!stopped && (remaining = deadline - System.nanoTime()) > 0) {
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
        }
    }

    private void closeCurrent() {
        Closeable toClose;
        synchronized (lock) {
            toClose = current;
            current = null;
        }
        closeQuietly(toClose);
    }

    /** A normal end of a stream we did not close means the daemon dropped us. */
    private static String codeOf(Throwable end) {
        if (end instanceof DockerConnectionException e) {
            return e.getCode().name();
        }
        return end == null ? DockerErrorCode.UNREACHABLE.name() : "UNAVAILABLE";
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception e) {
            // closing is best effort, the stream is gone either way
        }
    }
}
