package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.net.SocketTimeoutException;
import java.nio.file.AccessDeniedException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Shared by the docker/client services: runs a Docker call with the configured read timeout as overall
 * deadline and maps every failure to a {@link DockerConnectionException}.
 */
class DockerCallSupport {

    private static final Logger log = LoggerFactory.getLogger(DockerCallSupport.class);

    private final DockerHostConnectionManager manager;

    DockerCallSupport(DockerHostConnectionManager manager) {
        this.manager = manager;
    }

    DockerHostConnectionManager manager() {
        return manager;
    }

    /** Runs {@code call} on the cached client of {@code hostId}; evicts the client when the deadline expires. */
    <T> T call(UUID hostId, DockerEndpoint endpoint, Function<DockerClient, T> call) {
        return call(hostId, endpoint, Duration.ZERO, call);
    }

    /** Like {@link #call(UUID, DockerEndpoint, Function)} but the deadline is the read timeout plus {@code extra} (e.g. a stop grace period). */
    <T> T call(UUID hostId, DockerEndpoint endpoint, Duration extra, Function<DockerClient, T> call) {
        try {
            DockerClient client = manager.client(hostId, endpoint);
            return callWithDeadline(extra, () -> call.apply(client), () -> manager.evict(hostId));
        } catch (Exception e) {
            throw translate(e);
        }
    }

    /**
     * Runs {@code call} with the configured read timeout as overall deadline. The Unix-socket transport
     * ignores socket timeouts, so on expiry {@code abort} closes the connection to free the hung call.
     */
    <T> T callWithDeadline(Callable<T> call, Runnable abort) throws Exception {
        return callWithDeadline(Duration.ZERO, call, abort);
    }

    <T> T callWithDeadline(Duration extra, Callable<T> call, Runnable abort) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                result.complete(call.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(manager.readTimeout().plus(extra).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            abort.run();
            throw e;
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    DockerConnectionException translate(Exception e) {
        if (e instanceof DockerConnectionException known) {
            return known; // e.g. INVALID_TLS_MATERIAL from the client factory
        }
        DockerErrorCode code = classify(e);
        log.debug("Docker call failed ({}): {}", code, e.toString());
        String message = switch (code) {
            case TIMEOUT -> "The Docker daemon did not answer in time.";
            case TLS_ERROR -> "The TLS handshake with the Docker daemon failed.";
            case PERMISSION_DENIED -> "Permission denied accessing the Docker daemon.";
            case UNREACHABLE -> "The Docker daemon is not reachable.";
            case INVALID_TLS_MATERIAL -> "The TLS certificate or key is not valid PEM.";
            case NOT_FOUND -> "The resource does not exist.";
            case IN_USE -> "The resource is in use.";
            case BUILTIN_NETWORK -> "Built-in networks cannot be removed.";
            case INVALID_STATE -> "The container is not in a state that allows this action.";
            case PULL_FAILED -> "The image could not be pulled.";
            case NAME_TAKEN -> "A resource with this name already exists.";
            case START_FAILED -> "The container could not be started.";
            case INVALID_PATH -> "The path in the container is not valid.";
            case SIZE_LIMIT_EXCEEDED -> "The transfer exceeds the size limit.";
            case DIRECTORY_TOO_LARGE -> "The directory is too large to list.";
            case RECREATE_FAILED -> "The container could not be recreated.";
            case UPDATE_REJECTED -> "The daemon refused the new resource limits.";
            case INVALID_VOLUME_NAME -> "The volume name is not valid.";
            case INVALID_ARCHIVE -> "The archive is not a safe archive.";
            case BUILD_FAILED -> "The image could not be built.";
            case PUSH_FAILED -> "The image could not be pushed.";
            case INVALID_RESPONSE -> "The Docker daemon returned an unexpected response.";
        };
        return new DockerConnectionException(code, message, e);
    }

    private static DockerErrorCode classify(Throwable error) {
        DockerErrorCode result = DockerErrorCode.UNREACHABLE;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof NotFoundException) {
                return DockerErrorCode.NOT_FOUND;
            }
            if (t instanceof NotModifiedException) {
                return DockerErrorCode.INVALID_STATE;
            }
            if (t instanceof ConflictException) {
                return DockerErrorCode.IN_USE;
            }
            if (t instanceof SocketTimeoutException || t instanceof TimeoutException
                    || t.getClass().getSimpleName().contains("Timeout")) {
                return DockerErrorCode.TIMEOUT;
            }
            if (t instanceof SSLException) {
                return DockerErrorCode.TLS_ERROR;
            }
            if (t instanceof AccessDeniedException
                    || (t.getMessage() != null && t.getMessage().toLowerCase().contains("permission denied"))) {
                result = DockerErrorCode.PERMISSION_DENIED;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return result;
    }
}
