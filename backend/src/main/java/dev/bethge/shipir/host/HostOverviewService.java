package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.client.DockerSystemService;
import dev.bethge.shipir.docker.dto.DockerSystemInfoDto;
import dev.bethge.shipir.host.dto.HostOverviewResponse;
import dev.bethge.shipir.host.dto.HostOverviewResponse.Containers;
import dev.bethge.shipir.host.dto.HostOverviewResponse.Status;
import dev.bethge.shipir.permission.CurrentAccess;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Queries every environment live and in parallel. One slow or broken environment becomes {@code DOWN} with an
 * error code and never delays or fails the others: all share one deadline of {@code shipir.host.overview-timeout}.
 */
@Service
@EnableConfigurationProperties(HostOverviewProperties.class)
public class HostOverviewService {

    private static final Logger log = LoggerFactory.getLogger(HostOverviewService.class);

    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final DockerSystemService docker;
    private final DockerHostConnectionManager connections;
    private final HostOverviewProperties properties;
    private final CurrentAccess access;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    HostOverviewService(DockerHostRepository hosts, HostEndpointResolver endpoints, DockerSystemService docker,
                        DockerHostConnectionManager connections, HostOverviewProperties properties,
                        CurrentAccess access) {
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.docker = docker;
        this.connections = connections;
        this.properties = properties;
        this.access = access;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Live state of the hosts the signed-in user may see. Without authentication on the thread (health check scheduler)
     * every host is queried.
     */
    public List<HostOverviewResponse> overview() {
        var visible = access.visibleHostIds();
        List<DockerHost> all = hosts.findAll().stream()
                .filter(h -> visible.isEmpty() || visible.get().contains(h.getId()))
                .sorted(Comparator.comparing(DockerHost::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<Future<HostOverviewResponse>> futures = all.stream()
                .map(h -> executor.submit(() -> query(h)))
                .toList();
        long deadline = System.nanoTime() + properties.overviewTimeout().toNanos();
        List<HostOverviewResponse> result = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            result.add(await(all.get(i), futures.get(i), deadline));
        }
        return result;
    }

    private HostOverviewResponse await(DockerHost host, Future<HostOverviewResponse> future, long deadline) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return down(host, "TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return down(host, "INTERRUPTED");
        } catch (ExecutionException e) {
            return down(host, "UNKNOWN");
        }
    }

    /** Never throws: every failure of one environment is reported as {@code DOWN}. */
    private HostOverviewResponse query(DockerHost host) {
        try {
            DockerSystemInfoDto info = docker.info(host.getId(), endpoints.endpointOf(host));
            return new HostOverviewResponse(host.getId(), host.getName(), host.getConnectionType(), endpointOf(host),
                    Status.UP, null, info.dockerVersion(), info.cpus(), info.memoryBytes(),
                    new Containers(info.containersRunning(), info.containersStopped(), info.containersHealthy(),
                            info.containersUnhealthy()),
                    info.images(), info.volumes());
        } catch (DockerConnectionException e) {
            return down(host, e.getCode().name());
        } catch (EncryptionKeyMissingException e) {
            return down(host, "ENCRYPTION_KEY_MISSING");
        } catch (SecretDecryptionException e) {
            return down(host, "DECRYPTION_FAILED");
        } catch (RuntimeException e) {
            // class only: the message could contain connection details
            log.warn("Overview of environment {} failed: {}", host.getId(), e.getClass().getSimpleName());
            return down(host, "UNKNOWN");
        }
    }

    private HostOverviewResponse down(DockerHost host, String error) {
        return new HostOverviewResponse(host.getId(), host.getName(), host.getConnectionType(), endpointOf(host),
                Status.DOWN, error, null, null, null, null, null, null);
    }

    /** Display form only; never contains credentials. */
    private String endpointOf(DockerHost host) {
        if (host.getConnectionType() == ConnectionType.LOCAL) {
            String socket = ((DockerEndpoint.Local) connections.localEndpoint()).socketPath();
            return socket.startsWith("unix://") ? socket : "unix://" + socket;
        }
        return "tcp://" + host.getHost() + ":" + host.getPort();
    }
}
