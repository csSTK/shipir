package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Info;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ConnectionTestResult;
import dev.bethge.shipir.docker.dto.DockerSystemInfoDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLException;
import java.net.SocketTimeoutException;
import java.nio.file.AccessDeniedException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class DockerSystemServiceImpl implements DockerSystemService {

    private static final Logger log = LoggerFactory.getLogger(DockerSystemServiceImpl.class);

    private final DockerHostConnectionManager manager;

    public DockerSystemServiceImpl(DockerHostConnectionManager manager) {
        this.manager = manager;
    }

    @Override
    public ConnectionTestResult ping(DockerEndpoint endpoint) {
        try (DockerClient client = manager.createClient(endpoint)) {
            String version = callWithDeadline(() -> client.versionCmd().exec().getVersion(), () -> closeQuietly(client));
            return new ConnectionTestResult(true, version, null, null);
        } catch (Exception e) {
            DockerConnectionException failure = translate(e);
            return new ConnectionTestResult(false, null, failure.getCode(), failure.getMessage());
        }
    }

    @Override
    public DockerSystemInfoDto info(UUID hostId, DockerEndpoint endpoint) {
        DockerClient client = manager.client(hostId, endpoint);
        try {
            return callWithDeadline(() -> collectInfo(client), () -> manager.evict(hostId));
        } catch (Exception e) {
            throw translate(e);
        }
    }

    private static DockerSystemInfoDto collectInfo(DockerClient client) {
        Info info = client.infoCmd().exec();
        List<Container> containers = client.listContainersCmd().withShowAll(true).exec();
        // docker-java returns null instead of an empty list when the daemon has no volumes
        List<InspectVolumeResponse> volumeList = client.listVolumesCmd().exec().getVolumes();
        int volumes = volumeList == null ? 0 : volumeList.size();
        return new DockerSystemInfoDto(
                info.getServerVersion(),
                nullToZero(info.getNCPU()),
                nullToZero(info.getMemTotal()),
                nullToZero(info.getContainersRunning()),
                nullToZero(info.getContainersStopped()),
                countStatus(containers, "(healthy)"),
                countStatus(containers, "(unhealthy)"),
                nullToZero(info.getImages()),
                volumes);
    }

    /**
     * Runs {@code call} with the configured read timeout as overall deadline. The Unix-socket transport
     * ignores socket timeouts, so on expiry {@code abort} closes the connection to free the hung call.
     */
    private <T> T callWithDeadline(Callable<T> call, Runnable abort) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                result.complete(call.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(manager.readTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            abort.run();
            throw e;
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    private static void closeQuietly(DockerClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.debug("Closing Docker client failed: {}", e.toString());
        }
    }

    /** The health state is only available as suffix of the status text, e.g. {@code Up 5 seconds (healthy)}. */
    private static int countStatus(List<Container> containers, String marker) {
        return (int) containers.stream()
                .filter(c -> c.getStatus() != null && c.getStatus().contains(marker))
                .count();
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static long nullToZero(Long value) {
        return value == null ? 0 : value;
    }

    private DockerConnectionException translate(Exception e) {
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
        };
        return new DockerConnectionException(code, message, e);
    }

    private static DockerErrorCode classify(Throwable error) {
        DockerErrorCode result = DockerErrorCode.UNREACHABLE;
        for (Throwable t = error; t != null; t = t.getCause()) {
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
