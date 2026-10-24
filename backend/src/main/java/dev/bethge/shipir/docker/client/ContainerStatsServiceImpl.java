package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.BlkioStatEntry;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.StatisticNetworksConfig;
import com.github.dockerjava.api.model.Statistics;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ContainerStatsDto;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class ContainerStatsServiceImpl implements ContainerStatsService {

    private final DockerCallSupport calls;

    public ContainerStatsServiceImpl(DockerHostConnectionManager manager) {
        this.calls = new DockerCallSupport(manager);
    }

    @Override
    public Closeable stream(UUID hostId, DockerEndpoint endpoint, String containerId,
                            Consumer<ContainerStatsDto> onSample) {
        return stream(hostId, endpoint, containerId, onSample, error -> { });
    }

    @Override
    public Closeable stream(UUID hostId, DockerEndpoint endpoint, String containerId,
                            Consumer<ContainerStatsDto> onSample, Consumer<Throwable> onEnd) {
        // the stats call is asynchronous and would hide an unknown id, so check it first
        calls.call(hostId, endpoint, client -> client.inspectContainerCmd(containerId).exec());
        StatsCallback callback = new StatsCallback(onSample, onEnd);
        return calls.call(hostId, endpoint, client -> client.statsCmd(containerId).exec(callback));
    }

    @Override
    public ContainerStatsDto snapshot(UUID hostId, DockerEndpoint endpoint, String containerId) {
        return calls.call(hostId, endpoint, client -> {
            CompletableFuture<ContainerStatsDto> first = new CompletableFuture<>();
            ResultCallback.Adapter<Statistics> callback = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Statistics stats) {
                    first.complete(toDto(stats));
                }

                @Override
                public void onError(Throwable throwable) {
                    first.completeExceptionally(throwable);
                    super.onError(throwable);
                }
            };
            try (callback) {
                client.statsCmd(containerId).withNoStream(true).exec(callback);
                return first.get();
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(e.getCause()); // cause chain is classified by DockerCallSupport
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Converts a raw sample; CPU from the delta to the sample's own {@code precpu_stats}. */
    static ContainerStatsDto toDto(Statistics s) {
        long memUsage = 0;
        long memLimit = 0;
        MemoryStatsConfig mem = s.getMemoryStats();
        if (mem != null) {
            memUsage = nz(mem.getUsage());
            memLimit = nz(mem.getLimit());
            if (mem.getStats() != null) {
                // cgroup v1: total_inactive_file (older: cache); cgroup v2: inactive_file
                Long cache = mem.getStats().getTotalInactiveFile();
                if (cache == null) cache = mem.getStats().getInactiveFile();
                if (cache == null) cache = mem.getStats().getCache();
                if (cache != null && cache <= memUsage) memUsage -= cache;
            }
        }
        double memPercent = memLimit > 0 ? (double) memUsage / memLimit * 100.0 : 0.0;

        long rx = 0;
        long tx = 0;
        if (s.getNetworks() != null) {
            for (StatisticNetworksConfig n : s.getNetworks().values()) {
                rx += nz(n.getRxBytes());
                tx += nz(n.getTxBytes());
            }
        }

        long read = 0;
        long write = 0;
        if (s.getBlkioStats() != null) {
            List<BlkioStatEntry> entries = s.getBlkioStats().getIoServiceBytesRecursive();
            if (entries != null) {
                for (BlkioStatEntry e : entries) {
                    if ("read".equalsIgnoreCase(e.getOp())) read += nz(e.getValue());
                    else if ("write".equalsIgnoreCase(e.getOp())) write += nz(e.getValue());
                }
            }
        }
        return new ContainerStatsDto(parseTime(s.getRead()), cpuPercent(s.getCpuStats(), s.getPreCpuStats()),
                memUsage, memLimit, memPercent, rx, tx, read, write);
    }

    private static double cpuPercent(CpuStatsConfig cpu, CpuStatsConfig pre) {
        if (cpu == null || pre == null || cpu.getCpuUsage() == null || pre.getCpuUsage() == null) return 0.0;
        if (nz(pre.getCpuUsage().getTotalUsage()) == 0 && nz(pre.getSystemCpuUsage()) == 0) return 0.0; // no baseline yet
        double cpuDelta = nz(cpu.getCpuUsage().getTotalUsage()) - nz(pre.getCpuUsage().getTotalUsage());
        double systemDelta = nz(cpu.getSystemCpuUsage()) - nz(pre.getSystemCpuUsage());
        if (cpuDelta <= 0 || systemDelta <= 0) return 0.0;
        long cores = nz(cpu.getOnlineCpus());
        if (cores == 0 && cpu.getCpuUsage().getPercpuUsage() != null) cores = cpu.getCpuUsage().getPercpuUsage().size();
        if (cores == 0) cores = 1;
        return cpuDelta / systemDelta * cores * 100.0;
    }

    private static Instant parseTime(String read) {
        try {
            return read == null ? Instant.now() : Instant.parse(read);
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    static final class StatsCallback extends ResultCallback.Adapter<Statistics> {

        private final Consumer<ContainerStatsDto> onSample;
        private final Consumer<Throwable> onEnd;
        private final AtomicBoolean ended = new AtomicBoolean();

        StatsCallback(Consumer<ContainerStatsDto> onSample, Consumer<Throwable> onEnd) {
            this.onSample = onSample;
            this.onEnd = onEnd;
        }

        @Override
        public void onNext(Statistics stats) {
            onSample.accept(toDto(stats));
        }

        private void finish(Throwable error) {
            if (ended.compareAndSet(false, true)) onEnd.accept(error);
        }

        @Override
        public void onError(Throwable throwable) {
            finish(throwable); // first: super.onError closes, whose close() would finish(null)
            super.onError(throwable);
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
