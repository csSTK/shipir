package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.ViewerErrorCodes;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.client.DockerSystemService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.DockerSystemInfoDto;
import dev.bethge.shipir.host.dto.HostOverviewResponse;
import dev.bethge.shipir.host.dto.HostOverviewResponse.Containers;
import dev.bethge.shipir.host.dto.HostOverviewResponse.Status;
import dev.bethge.shipir.permission.CurrentAccess;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final HostTagService tags;
    private final HostResourcesService resources;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    HostOverviewService(DockerHostRepository hosts, HostEndpointResolver endpoints, DockerSystemService docker,
                        DockerHostConnectionManager connections, HostOverviewProperties properties,
                        CurrentAccess access, HostTagService tags, HostResourcesService resources) {
        this.tags = tags;
        this.resources = resources;
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
        boolean admin = access.isAdmin();
        List<DockerHost> all = hosts.findAll().stream()
                .filter(h -> visible.isEmpty() || visible.get().contains(h.getId()))
                .sorted(Comparator.comparing(DockerHost::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        Map<UUID, List<String>> tagsByHost = tags.tagsByHost(all.stream().map(DockerHost::getId).toList());
        SecurityContext context = SecurityContextHolder.getContext();
        // Decided on the request thread: the virtual threads have no subject and would see every host as fully visible.
        Set<UUID> restricted = all.stream().map(DockerHost::getId).filter(id -> !access.hostFullyVisible(id))
                .collect(Collectors.toSet());
        List<Future<HostOverviewResponse>> futures = all.stream()
                .map(h -> executor.submit(() -> query(h, tagsOf(tagsByHost, h), admin, restricted.contains(h.getId()),
                        context)))
                .toList();
        long deadline = System.nanoTime() + properties.overviewTimeout().toNanos();
        List<HostOverviewResponse> result = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            result.add(await(all.get(i), futures.get(i), deadline, tagsOf(tagsByHost, all.get(i)), admin));
        }
        return result;
    }

    private static List<String> tagsOf(Map<UUID, List<String>> tagsByHost, DockerHost host) {
        return tagsByHost.getOrDefault(host.getId(), List.of());
    }

    private HostOverviewResponse await(DockerHost host, Future<HostOverviewResponse> future, long deadline,
                                       List<String> tagList, boolean admin) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return down(host, ViewerErrorCodes.forViewer("TIMEOUT", admin), tagList);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return down(host, "INTERRUPTED", tagList);
        } catch (ExecutionException e) {
            return down(host, "UNKNOWN", tagList);
        }
    }

    /** Never throws: every failure of one environment is reported as {@code DOWN}. */
    private HostOverviewResponse query(DockerHost host, List<String> tagList, boolean admin, boolean restricted,
                                       SecurityContext context) {
        try {
            DockerSystemInfoDto info = docker.info(host.getId(), endpoints.endpointOf(host));
            // Visible only through deployment grants: counts of what the lists show (own containers), and no host
            // facts (version, CPUs, memory) or image/volume counts at all (null), never the host-wide numbers.
            Containers containers = restricted ? visibleContainers(host, context)
                    : new Containers(info.containersRunning(), info.containersStopped(), info.containersHealthy(),
                            info.containersUnhealthy());
            return new HostOverviewResponse(host.getId(), host.getName(), host.getConnectionType(), endpointOf(host),
                    Status.UP, null, restricted ? null : info.dockerVersion(), restricted ? null : info.cpus(),
                    restricted ? null : info.memoryBytes(), containers,
                    restricted ? null : info.images(), restricted ? null : info.volumes(), tagList);
        } catch (DockerConnectionException e) {
            return down(host, ViewerErrorCodes.forViewer(e.getCode(), admin), tagList);
        } catch (EncryptionKeyMissingException e) {
            return down(host, "ENCRYPTION_KEY_MISSING", tagList);
        } catch (SecretDecryptionException e) {
            return down(host, "DECRYPTION_FAILED", tagList);
        } catch (RuntimeException e) {
            // class only: the message could contain connection details
            log.warn("Overview of environment {} failed: {}", host.getId(), e.getClass().getSimpleName());
            return down(host, "UNKNOWN", tagList);
        }
    }

    /** Runs on a virtual thread: carries the caller's security context so the list filter applies. */
    private Containers visibleContainers(DockerHost host, SecurityContext context) {
        SecurityContextHolder.setContext(context);
        try {
            List<ContainerDto> list = resources.containers(host.getId());
            int running = (int) list.stream().filter(c -> "running".equalsIgnoreCase(c.state())).count();
            int healthy = (int) list.stream().filter(c -> "healthy".equalsIgnoreCase(c.health())).count();
            int unhealthy = (int) list.stream().filter(c -> "unhealthy".equalsIgnoreCase(c.health())).count();
            return new Containers(running, list.size() - running, healthy, unhealthy);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private HostOverviewResponse down(DockerHost host, String error, List<String> tagList) {
        return new HostOverviewResponse(host.getId(), host.getName(), host.getConnectionType(), endpointOf(host),
                Status.DOWN, error, null, null, null, null, null, null, tagList);
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
