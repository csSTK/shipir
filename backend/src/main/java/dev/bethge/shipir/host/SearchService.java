package dev.bethge.shipir.host;

import dev.bethge.shipir.compose.DeploymentService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.host.dto.HostDeploymentResponse;
import dev.bethge.shipir.host.dto.SearchResponse;
import dev.bethge.shipir.host.dto.SearchResult;
import dev.bethge.shipir.host.dto.SearchResult.Type;
import dev.bethge.shipir.permission.CurrentAccess;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Global search over hosts, deployments (managed and live) and containers. Hosts and managed deployments come from
 * the database, live deployments and containers from the visible hosts in parallel under the shared deadline
 * {@code shipir.host.overview-timeout}. A slow or unreachable host contributes nothing and never fails the request.
 * Visibility is delegated to {@link HostResourcesService} and {@link DeploymentService}, so it matches the list endpoints.
 */
@Service
public class SearchService {

    static final int MIN_QUERY_LENGTH = 2;
    static final int MAX_RESULTS = 50;

    private final DockerHostRepository hosts;
    private final HostResourcesService resources;
    private final DeploymentService deployments;
    private final HostOverviewProperties properties;
    private final CurrentAccess access;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    SearchService(DockerHostRepository hosts, HostResourcesService resources, DeploymentService deployments,
                  HostOverviewProperties properties, CurrentAccess access) {
        this.hosts = hosts;
        this.resources = resources;
        this.deployments = deployments;
        this.properties = properties;
        this.access = access;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public SearchResponse search(String query) {
        String q = query == null ? "" : query.trim();
        if (q.length() < MIN_QUERY_LENGTH) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_QUERY",
                    "Search needs at least " + MIN_QUERY_LENGTH + " characters");
        }
        String needle = q.toLowerCase(Locale.ROOT);
        var visible = access.visibleHostIds();
        List<DockerHost> visibleHosts = hosts.findAll().stream()
                .filter(h -> visible.isEmpty() || visible.get().contains(h.getId()))
                .sorted(Comparator.comparing(DockerHost::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        SecurityContext context = SecurityContextHolder.getContext();
        List<Future<Live>> futures = visibleHosts.stream()
                .map(h -> executor.submit(() -> live(h, context)))
                .toList();

        List<SearchResult> hostHits = new ArrayList<>();
        List<SearchResult> deploymentHits = new ArrayList<>();
        List<SearchResult> containerHits = new ArrayList<>();
        long deadline = System.nanoTime() + properties.overviewTimeout().toNanos();
        for (int i = 0; i < visibleHosts.size(); i++) {
            DockerHost host = visibleHosts.get(i);
            if (matches(host.getName(), needle)) {
                hostHits.add(new SearchResult(Type.HOST, host.getId().toString(), host.getName(), host.getId(),
                        host.getName(), false, null));
            }
            deployments.list(host.getId()).stream()
                    .filter(d -> matches(d.name(), needle))
                    .forEach(d -> deploymentHits.add(new SearchResult(Type.DEPLOYMENT, d.id().toString(), d.name(),
                            host.getId(), host.getName(), true, d.status().name())));
            Live live = await(futures.get(i), deadline);
            for (HostDeploymentResponse d : live.deployments()) {
                if (!d.managed() && matches(d.name(), needle)) {
                    deploymentHits.add(new SearchResult(Type.DEPLOYMENT, null, d.name(), host.getId(), host.getName(),
                            false, d.status().name()));
                }
            }
            for (ContainerDto c : live.containers()) {
                if (matches(c.name(), needle) || matches(c.image(), needle)) {
                    containerHits.add(new SearchResult(Type.CONTAINER, c.id(), c.name(), host.getId(), host.getName(),
                            c.composeProject() != null, c.image()));
                }
            }
        }
        List<SearchResult> all = new ArrayList<>(hostHits);
        all.addAll(deploymentHits);
        all.addAll(containerHits);
        return new SearchResponse(all.stream().limit(MAX_RESULTS).toList(), all.size() > MAX_RESULTS);
    }

    private static boolean matches(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Runs on a virtual thread: carries the caller's security context so visibility filtering applies. */
    private Live live(DockerHost host, SecurityContext context) {
        SecurityContextHolder.setContext(context);
        try {
            return new Live(resources.deployments(host.getId()), resources.containers(host.getId()));
        } catch (RuntimeException e) {
            return Live.EMPTY;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Live await(Future<Live> future, long deadline) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Live.EMPTY;
        } catch (Exception e) {
            future.cancel(true);
            return Live.EMPTY;
        }
    }

    private record Live(List<HostDeploymentResponse> deployments, List<ContainerDto> containers) {
        static final Live EMPTY = new Live(List.of(), List.of());
    }
}
