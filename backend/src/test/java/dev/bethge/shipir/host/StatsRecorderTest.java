package dev.bethge.shipir.host;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ContainerStatsService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ContainerStatsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Recorder with a stubbed Docker side and a mutable clock against a real Postgres. The recorder bean is off in tests
 * ({@code shipir.stats.recorder-enabled=false}), so it is built by hand.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class StatsRecorderTest {

    /** Clock the test can move. */
    static final class MutableClock extends Clock {
        volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DockerHostRepository hosts;
    @Autowired
    StatsSampleRepository samples;

    private final ContainerService containers = mock(ContainerService.class);
    private final ContainerStatsService stats = mock(ContainerStatsService.class);
    private final HostEndpointResolver endpoints = mock(HostEndpointResolver.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-01T10:00:00Z"));
    private final Map<String, List<ContainerDto>> containersByHost = new ConcurrentHashMap<>();
    private StatsRecorder recorder;
    private UUID owner;

    @BeforeEach
    void setUp() {
        owner = jdbc.queryForObject(
                "INSERT INTO users (username, password_hash, role) VALUES ('rec-owner', 'x', 'ADMIN') RETURNING id",
                UUID.class);
        jdbc.update("DELETE FROM docker_hosts");
        when(endpoints.endpointOf(any())).thenAnswer(inv ->
                new DockerEndpoint.Local(((DockerHost) inv.getArgument(0)).getName()));
        when(containers.listContainers(any(), any())).thenAnswer(inv -> {
            String name = ((DockerEndpoint.Local) inv.getArgument(1)).socketPath();
            if (name.startsWith("hang")) {
                Thread.sleep(60_000);
            }
            if (name.startsWith("down")) {
                throw new DockerConnectionException(DockerErrorCode.UNREACHABLE, "refused", null);
            }
            return containersByHost.getOrDefault(name, List.of());
        });
        when(stats.snapshot(any(), any(), anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(2);
            if (id.startsWith("broken")) {
                throw new DockerConnectionException(DockerErrorCode.NOT_FOUND, "gone", null);
            }
            return new ContainerStatsDto(clock.instant(), 12.5, 100, 1000, 10, 1, 2, 3, 4);
        });
        recorder = new StatsRecorder(hosts, endpoints, containers, stats, samples,
                new StatsProperties(Duration.ofSeconds(60), Duration.ofMillis(500), 200, 300, 50), clock);
    }

    private UUID host(String name, ContainerDto... running) {
        UUID id = hosts.save(DockerHost.tcp(name, name + ".local", 2375, null, null, null, owner)).getId();
        containersByHost.put(name, List.of(running));
        return id;
    }

    private static ContainerDto container(String id, String state, String project) {
        return new ContainerDto(id, id, "img", state, "Up", null, Instant.now(), List.of(), project, null);
    }

    private int rows(UUID host) {
        return jdbc.queryForObject("SELECT count(*) FROM stats_samples WHERE host_id = ?", Integer.class, host);
    }

    @Test
    void writesOneSamplePerRunningContainerWithTheLiveStatsValues() {
        UUID h = host("rec-a", container("c1", "running", "web"), container("c2", "running", null));
        recorder.tick();

        assertThat(rows(h)).isEqualTo(2);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM stats_samples WHERE host_id = ? AND container_id = 'c1'", h);
        assertThat(row.get("compose_project")).isEqualTo("web");
        assertThat(((java.sql.Timestamp) row.get("ts")).toInstant()).isEqualTo(clock.instant());
        assertThat(((Number) row.get("cpu_percent")).doubleValue()).isEqualTo(12.5);
        assertThat(((Number) row.get("mem_bytes")).longValue()).isEqualTo(100);
        assertThat(((Number) row.get("net_rx_bytes")).longValue()).isEqualTo(1);
        assertThat(((Number) row.get("net_tx_bytes")).longValue()).isEqualTo(2);
        assertThat(((Number) row.get("blk_read_bytes")).longValue()).isEqualTo(3);
        assertThat(((Number) row.get("blk_write_bytes")).longValue()).isEqualTo(4);
    }

    @Test
    void skipsContainersThatAreNotRunning() {
        UUID h = host("rec-a", container("up", "running", null), container("off", "exited", null),
                container("paused", "paused", null), container("made", "created", null));
        recorder.tick();

        assertThat(jdbc.queryForList("SELECT container_id FROM stats_samples WHERE host_id = ?", String.class, h))
                .containsExactly("up");
    }

    @Test
    void unreachableHostDoesNotBlockTheOtherHosts() {
        UUID bad = host("down-1", container("x", "running", null));
        UUID good = host("rec-good", container("c1", "running", null));
        recorder.tick();

        assertThat(rows(bad)).isZero();
        assertThat(rows(good)).isEqualTo(1);
    }

    @Test
    void hangingHostIsCutOffAtTheTimeoutAndOthersAreStillRecorded() {
        UUID hanging = host("hang-1", container("x", "running", null));
        UUID good = host("rec-good", container("c1", "running", null));
        long start = System.nanoTime();
        recorder.tick();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(rows(hanging)).isZero();
        assertThat(rows(good)).isEqualTo(1);
    }

    @Test
    void containerThatCannotBeSampledOnlySkipsItself() {
        UUID h = host("rec-a", container("broken-1", "running", null), container("fine", "running", null));
        recorder.tick();

        assertThat(jdbc.queryForList("SELECT container_id FROM stats_samples WHERE host_id = ?", String.class, h))
                .containsExactly("fine");
    }

    @Test
    void everyTickAddsRowsAtItsOwnTimestampAndRepeatingAnInstantDoesNotDuplicate() {
        UUID h = host("rec-a", container("c1", "running", null));
        recorder.tick();
        recorder.tick();
        assertThat(rows(h)).isEqualTo(1);

        clock.now = clock.now.plusSeconds(60);
        recorder.tick();
        assertThat(rows(h)).isEqualTo(2);
    }

    @Test
    void stopsRecordingAHostWhenItsContainersStop() {
        UUID h = host("rec-a", container("c1", "running", null));
        recorder.tick();
        containersByHost.put("rec-a", List.of(container("c1", "exited", null)));
        clock.now = clock.now.plusSeconds(60);
        recorder.tick();

        assertThat(rows(h)).isEqualTo(1);
    }

    private StatsRecorder cappedRecorder(int maxContainers) {
        return new StatsRecorder(hosts, endpoints, containers, stats, samples,
                new StatsProperties(Duration.ofSeconds(60), Duration.ofMillis(500), maxContainers, 300, 50), clock);
    }

    @Test
    void recordsAtMostMaxContainersPerHostAndNeverSamplesTheRest() {
        UUID h = host("rec-a", container("c1", "running", null), container("c2", "running", null),
                container("c3", "running", null));
        cappedRecorder(2).tick();

        assertThat(rows(h)).isEqualTo(2);
        org.mockito.Mockito.verify(stats, org.mockito.Mockito.never()).snapshot(any(), any(), org.mockito.ArgumentMatchers.eq("c3"));
    }

    @Test
    void hittingTheCapLogsOneWarnPerHostAndTickWithCountsOnly() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(StatsRecorder.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            host("rec-a", container("c1", "running", null), container("c2", "running", null),
                    container("c3", "running", null));
            host("rec-b", container("d1", "running", null));
            StatsRecorder capped = cappedRecorder(2);
            capped.tick();
            clock.now = clock.now.plusSeconds(60);
            capped.tick();

            var warns = appender.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN).toList();
            assertThat(warns).hasSize(2); // one host over the cap, two ticks
            assertThat(warns.get(0).getFormattedMessage()).contains("3").contains("2")
                    .doesNotContainPattern("\\bc1\\b").doesNotContainPattern("\\bc3\\b"); // host UUID may contain "c3" by chance
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void samplesAreDeletedWithTheirHost() {
        UUID h = host("rec-a", container("c1", "running", null), container("c2", "running", null));
        recorder.tick();
        assertThat(rows(h)).isEqualTo(2);

        jdbc.update("DELETE FROM docker_hosts WHERE id = ?", h);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stats_samples", Integer.class)).isZero();
    }

    @Test
    void recorderIsOffInTestsByProperty(@Autowired org.springframework.context.ApplicationContext context) {
        assertThat(context.getBeanNamesForType(StatsRecorder.class)).isEmpty();
    }
}
