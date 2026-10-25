package dev.bethge.shipir.host;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Plain SQL over {@code stats_samples} (bulk inserts and time-bucket aggregation); every value is a bound parameter. */
@Repository
class StatsSampleRepository {

    record Row(UUID hostId, String containerId, String composeProject, Instant ts, double cpuPercent, long memBytes,
               long netRxBytes, long netTxBytes, long blkReadBytes, long blkWriteBytes) {
    }

    /** One aggregated bucket: {@code bucket} is the bucket index (epoch seconds / bucket size). */
    record Bucket(String containerId, long bucket, double cpuPercent, long memBytes, long netRxBytes,
                  long netTxBytes, long blkReadBytes, long blkWriteBytes) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    StatsSampleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Batch insert; a sample that already exists (same host, container and timestamp) is kept. */
    void insertAll(List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        var batch = rows.stream().map(r -> new MapSqlParameterSource()
                .addValue("host", r.hostId())
                .addValue("container", r.containerId())
                .addValue("project", r.composeProject())
                .addValue("ts", Timestamp.from(r.ts()))
                .addValue("cpu", r.cpuPercent())
                .addValue("mem", r.memBytes())
                .addValue("rx", r.netRxBytes())
                .addValue("tx", r.netTxBytes())
                .addValue("br", r.blkReadBytes())
                .addValue("bw", r.blkWriteBytes())).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO stats_samples (host_id, container_id, compose_project, ts, cpu_percent, mem_bytes,
                    net_rx_bytes, net_tx_bytes, blk_read_bytes, blk_write_bytes)
                VALUES (:host, :container, :project, :ts, :cpu, :mem, :rx, :tx, :br, :bw)
                ON CONFLICT DO NOTHING
                """, batch);
    }

    /**
     * Ids of the containers of a host with samples in {@code [from, to]}, those with the newest sample first (ties by
     * id).
     *
     * @param container only this container, or null for all
     * @param projects  only containers of these compose projects, or null for no restriction (empty = nothing)
     */
    List<String> containersByRecency(UUID hostId, String container, Set<String> projects, Instant from, Instant to) {
        if (projects != null && projects.isEmpty()) {
            return List.of();
        }
        var params = new MapSqlParameterSource()
                .addValue("host", hostId)
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to));
        StringBuilder sql = new StringBuilder(
                "SELECT container_id FROM stats_samples WHERE host_id = :host AND ts >= :from AND ts <= :to");
        if (container != null) {
            sql.append(" AND container_id = :container");
            params.addValue("container", container);
        }
        if (projects != null) {
            sql.append(" AND compose_project IN (:projects)");
            params.addValue("projects", projects);
        }
        sql.append(" GROUP BY container_id ORDER BY max(ts) DESC, container_id");
        return jdbc.queryForList(sql.toString(), params, String.class);
    }

    /**
     * Aggregates the samples of the given containers of a host in {@code [from, to]} into buckets of
     * {@code bucketSeconds}, ordered by container and time. The caller has already applied the visibility rules to
     * {@code containerIds}.
     */
    List<Bucket> buckets(UUID hostId, Collection<String> containerIds, Instant from, Instant to,
                         long bucketSeconds) {
        if (containerIds.isEmpty()) {
            return List.of();
        }
        var params = new MapSqlParameterSource()
                .addValue("host", hostId)
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to))
                .addValue("bucket", bucketSeconds)
                .addValue("containers", containerIds);
        String sql = """
                SELECT container_id,
                       CAST(EXTRACT(EPOCH FROM ts) AS bigint) / :bucket AS bucket,
                       avg(cpu_percent) AS cpu, avg(mem_bytes) AS mem,
                       max(net_rx_bytes) AS rx, max(net_tx_bytes) AS tx,
                       max(blk_read_bytes) AS br, max(blk_write_bytes) AS bw
                FROM stats_samples
                WHERE host_id = :host AND ts >= :from AND ts <= :to AND container_id IN (:containers)
                GROUP BY container_id, bucket ORDER BY container_id, bucket
                """;
        return jdbc.query(sql, params, (rs, i) -> new Bucket(rs.getString("container_id"),
                rs.getLong("bucket"), rs.getDouble("cpu"), Math.round(rs.getDouble("mem")), rs.getLong("rx"),
                rs.getLong("tx"), rs.getLong("br"), rs.getLong("bw")));
    }
}
