package dev.bethge.shipir.host;

import dev.bethge.shipir.host.dto.StatsHistoryResponse;
import dev.bethge.shipir.host.dto.StatsHistoryResponse.Point;
import dev.bethge.shipir.host.dto.StatsHistoryResponse.Series;
import dev.bethge.shipir.permission.CurrentAccess;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads the recorded resource usage. Visibility mirrors the live stats topic: a fully visible host shows all
 * containers, a host visible only through deployment grants shows the containers of the visible managed deployments
 * (matched by the compose project stored with each sample, so the history of a removed container stays visible).
 * A container that is not visible simply yields no series, exactly like one that never existed. At most
 * {@code shipir.stats.max-series} series are returned (newest samples first), counted after the visibility filter.
 */
@Service
@EnableConfigurationProperties(StatsProperties.class)
public class StatsHistoryService {

    private static final Map<String, Duration> RANGES = Map.of(
            "1h", Duration.ofHours(1), "6h", Duration.ofHours(6), "24h", Duration.ofHours(24),
            "7d", Duration.ofDays(7), "30d", Duration.ofDays(30));

    private final DockerHostRepository hosts;
    private final StatsSampleRepository samples;
    private final CurrentAccess access;
    private final ManagedDeploymentLookup managed;
    private final StatsProperties properties;
    private final Clock clock;

    StatsHistoryService(DockerHostRepository hosts, StatsSampleRepository samples, CurrentAccess access,
                        ManagedDeploymentLookup managed, StatsProperties properties, Clock clock) {
        this.hosts = hosts;
        this.samples = samples;
        this.access = access;
        this.managed = managed;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param container optional exact container id
     * @param range     one of 1h, 6h, 24h, 7d, 30d
     */
    public StatsHistoryResponse history(UUID hostId, String container, String range) {
        if (!hosts.existsById(hostId) || !access.canView(hostId)) {
            throw new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found");
        }
        Duration window = RANGES.get(range);
        if (window == null) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "Range must be one of 1h, 6h, 24h, 7d, 30d");
        }
        if (container != null && !HostResourcesService.RESOURCE_ID.matcher(container).matches()) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_RESOURCE_ID", "Invalid resource id");
        }
        Instant to = clock.instant();
        Instant from = to.minus(window);
        long bucketSeconds = Math.max(properties.interval().toSeconds(),
                (window.toSeconds() + properties.maxPoints() - 2) / Math.max(1, properties.maxPoints() - 1));

        // visibility is applied while listing the containers, so the series cap only counts visible ones
        List<String> all = samples.containersByRecency(hostId, container, visibleProjects(hostId), from, to);
        List<String> shown = all.subList(0, Math.min(all.size(), properties.maxSeries()));
        Map<String, List<Point>> byContainer = new LinkedHashMap<>();
        shown.forEach(id -> byContainer.put(id, new ArrayList<>()));
        for (var b : samples.buckets(hostId, shown, from, to, bucketSeconds)) {
            byContainer.get(b.containerId())
                    .add(new Point(Instant.ofEpochSecond(b.bucket() * bucketSeconds), b.cpuPercent(), b.memBytes(),
                            b.netRxBytes(), b.netTxBytes(), b.blkReadBytes(), b.blkWriteBytes()));
        }
        List<Series> series = byContainer.entrySet().stream().map(e -> new Series(e.getKey(), e.getValue())).toList();
        return new StatsHistoryResponse(range, from, to, (int) bucketSeconds, all.size(), all.size() > shown.size(),
                series);
    }

    /** Null = every container of the host; otherwise the compose projects of the visible managed deployments. */
    private Set<String> visibleProjects(UUID hostId) {
        if (access.hostFullyVisible(hostId)) {
            return null;
        }
        Set<UUID> visible = access.visibleDeploymentIds(hostId).orElseThrow();
        return managed.managedDeploymentIds(hostId).entrySet().stream()
                .filter(e -> visible.contains(e.getValue())).map(Map.Entry::getKey).collect(Collectors.toSet());
    }
}
