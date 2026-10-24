package dev.bethge.shipir.docker.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Statistics;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ContainerStatsDto;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Sample conversion from fixture JSON (as the daemon sends it) and error mapping with a stubbed client. */
class ContainerStatsServiceTest {

    private static ContainerStatsDto convert(String fixture) throws IOException {
        try (InputStream in = ContainerStatsServiceTest.class.getResourceAsStream("/docker/" + fixture)) {
            return ContainerStatsServiceImpl.toDto(new ObjectMapper().readValue(in, Statistics.class));
        }
    }

    @Test
    void cgroupV1ComputesCpuFromDeltasMemoryWithoutCacheAndSumsNetworkAndBlockIo() throws Exception {
        ContainerStatsDto dto = convert("stats-cgroup-v1.json");

        assertThat(dto.time()).isEqualTo(Instant.parse("2026-10-24T10:00:01Z"));
        // (3e8-2e8)/(2e9-1e9) * 4 cpus * 100
        assertThat(dto.cpuPercent()).isCloseTo(40.0, within(0.0001));
        assertThat(dto.memoryUsageBytes()).isEqualTo(104857600L - 10485760L);
        assertThat(dto.memoryLimitBytes()).isEqualTo(1073741824L);
        assertThat(dto.memoryPercent()).isCloseTo(94371840.0 / 1073741824.0 * 100, within(0.0001));
        assertThat(dto.networkRxBytes()).isEqualTo(1200);
        assertThat(dto.networkTxBytes()).isEqualTo(600);
        assertThat(dto.blockReadBytes()).isEqualTo(5096);
        assertThat(dto.blockWriteBytes()).isEqualTo(8192);
    }

    @Test
    void cgroupV2UsesInactiveFileAndToleratesMissingLimitNetworkAndBlkio() throws Exception {
        ContainerStatsDto dto = convert("stats-cgroup-v2.json");

        assertThat(dto.cpuPercent()).isCloseTo(50.0, within(0.0001)); // one core assumed
        assertThat(dto.memoryUsageBytes()).isEqualTo(4000);
        assertThat(dto.memoryPercent()).isZero();
        assertThat(dto.networkRxBytes()).isZero();
        assertThat(dto.blockReadBytes()).isZero();
        assertThat(dto.blockWriteBytes()).isZero();
    }

    @Test
    void firstSampleWithoutCpuBaselineReportsZeroCpu() throws Exception {
        ContainerStatsDto dto = convert("stats-first-sample.json");
        assertThat(dto.cpuPercent()).isZero();
        assertThat(dto.memoryUsageBytes()).isZero();
    }

    @Test
    void endCallbackRunsOnceAndFailureIsReported() {
        List<Throwable> ends = new ArrayList<>();
        var cb = new ContainerStatsServiceImpl.StatsCallback(s -> { }, ends::add);
        RuntimeException failure = new RuntimeException("broken");
        cb.onError(failure);
        cb.onComplete();
        assertThat(ends).containsExactly(failure);
    }

    @Test
    void closeEndsStreamNormallyAndIsIdempotent() throws Exception {
        List<Throwable> ends = new ArrayList<>();
        var cb = new ContainerStatsServiceImpl.StatsCallback(s -> { }, ends::add);
        cb.close();
        cb.close();
        assertThat(ends).hasSize(1).containsNull();
    }

    private ContainerStatsServiceImpl serviceWithUnknownContainer() {
        DockerHostConnectionManager manager = mock(DockerHostConnectionManager.class);
        DockerClient client = mock(DockerClient.class, Answers.RETURNS_DEEP_STUBS);
        when(manager.readTimeout()).thenReturn(Duration.ofSeconds(1));
        when(manager.client(any(), any())).thenReturn(client);
        when(client.inspectContainerCmd("gone").exec()).thenThrow(new NotFoundException("x"));
        when(client.statsCmd("gone")).thenThrow(new NotFoundException("x"));
        return new ContainerStatsServiceImpl(manager);
    }

    @Test
    void unknownContainerIsNotFoundForStreamAndSnapshot() {
        var service = serviceWithUnknownContainer();
        var endpoint = new DockerEndpoint.Local("unix:///x.sock");
        UUID host = UUID.randomUUID();

        assertThatThrownBy(() -> service.stream(host, endpoint, "gone", s -> { }))
                .isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.snapshot(host, endpoint, "gone"))
                .isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }
}
