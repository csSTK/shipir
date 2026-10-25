package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.SystemScope;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ContainerStatsService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ContainerStatsDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Records the resource usage history: every {@code shipir.stats.interval} (60 s) one sample per running container of
 * every host, taken with {@link ContainerStatsService#snapshot} (the same numbers as the live stats topic) and
 * written as one batch per tick. Hosts are sampled in parallel under a shared deadline
 * ({@code shipir.stats.host-timeout}): an unreachable or hanging host is skipped for this tick and never delays the
 * others. Runs as the system (no user). Off with {@code shipir.stats.recorder-enabled=false}.
 *
 * <p>The retention purge deletes samples older than {@code stats_days}; the history endpoint downsamples on read.
 */
@Component
@ConditionalOnProperty(name = "shipir.stats.recorder-enabled", matchIfMissing = true)
class StatsRecorder {

    private static final Logger log = LoggerFactory.getLogger(StatsRecorder.class);

    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final ContainerService containers;
    private final ContainerStatsService stats;
    private final StatsSampleRepository samples;
    private final StatsProperties properties;
    private final Clock clock;

    StatsRecorder(DockerHostRepository hosts, HostEndpointResolver endpoints, ContainerService containers,
                  ContainerStatsService stats, StatsSampleRepository samples, StatsProperties properties,
                  Clock clock) {
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.containers = containers;
        this.stats = stats;
        this.samples = samples;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shipir.stats.interval:60s}", initialDelayString = "${shipir.stats.interval:60s}")
    void scheduledTick() {
        try {
            SystemScope.run(this::tick);
        } catch (RuntimeException e) {
            log.warn("Stats recording failed: {}", e.getClass().getSimpleName());
        }
    }

    /** One recording round; package-private for tests. */
    void tick() {
        Instant ts = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        List<DockerHost> all = hosts.findAll();
        long deadline = System.nanoTime() + properties.hostTimeout().toNanos();
        List<StatsSampleRepository.Row> rows = new ArrayList<>();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<List<StatsSampleRepository.Row>>> futures = new ArrayList<>();
            for (DockerHost host : all) {
                futures.add(pool.submit(() -> sampleHost(host, ts)));
            }
            for (int i = 0; i < futures.size(); i++) {
                rows.addAll(await(futures.get(i), deadline, all.get(i)));
            }
        } finally {
            pool.shutdownNow(); // a hanging host must not hold the tick: its worker is interrupted and abandoned
        }
        if (rows.isEmpty()) {
            return;
        }
        try {
            samples.insertAll(rows);
        } catch (RuntimeException e) {
            log.warn("Could not store {} stats samples: {}", rows.size(), e.getClass().getSimpleName());
        }
    }

    private List<StatsSampleRepository.Row> await(Future<List<StatsSampleRepository.Row>> future, long deadline,
                                                  DockerHost host) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.debug("Stats of host {} timed out", host.getId());
        } catch (java.util.concurrent.ExecutionException e) {
            log.debug("Stats of host {} failed: {}", host.getId(), e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return List.of();
    }

    private List<StatsSampleRepository.Row> sampleHost(DockerHost host, Instant ts) throws Exception {
        var endpoint = endpoints.endpointOf(host);
        List<ContainerDto> allRunning = containers.listContainers(host.getId(), endpoint).stream()
                .filter(c -> "running".equalsIgnoreCase(c.state())).toList();
        if (allRunning.size() > properties.maxContainers()) {
            log.warn("Host {} runs {} containers, recording only the first {} (shipir.stats.max-containers)",
                    host.getId(), allRunning.size(), properties.maxContainers());
        }
        List<ContainerDto> running = allRunning.stream().limit(properties.maxContainers()).toList();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<StatsSampleRepository.Row>> futures = new ArrayList<>();
            for (ContainerDto c : running) {
                futures.add(pool.submit(() -> {
                    try {
                        return toRow(host, c, ts, stats.snapshot(host.getId(), endpoint, c.id()));
                    } catch (RuntimeException e) {
                        return null; // container vanished or hiccup: skip it this tick
                    }
                }));
            }
            List<StatsSampleRepository.Row> result = new ArrayList<>();
            for (Future<StatsSampleRepository.Row> f : futures) {
                StatsSampleRepository.Row row = f.get();
                if (row != null) {
                    result.add(row);
                }
            }
            return result;
        } finally {
            pool.shutdownNow();
        }
    }

    private static StatsSampleRepository.Row toRow(DockerHost host, ContainerDto c, Instant ts,
                                                   ContainerStatsDto s) {
        return new StatsSampleRepository.Row(host.getId(), c.id(), c.composeProject(), ts,
                Math.max(0, s.cpuPercent()), Math.max(0, s.memoryUsageBytes()), Math.max(0, s.networkRxBytes()),
                Math.max(0, s.networkTxBytes()), Math.max(0, s.blockReadBytes()), Math.max(0, s.blockWriteBytes()));
    }
}
