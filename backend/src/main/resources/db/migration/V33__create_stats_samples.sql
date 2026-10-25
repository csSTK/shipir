-- V32 is reserved by another epic (host_tags); this migration deliberately takes V33.

-- One resource sample per running container and recording tick (default 60 s). The primary key doubles as the
-- lookup index (host, container, time range) and includes the time column, so the table can later be partitioned
-- by ts without changing the key. compose_project is stored with the sample so the history of a deleted container
-- stays filterable by the permissions of its deployment. Network and block I/O are cumulative counters as reported
-- by Docker; the cpu/memory values are the same as in the live stats topic.
CREATE TABLE stats_samples (
    host_id UUID NOT NULL REFERENCES docker_hosts(id) ON DELETE CASCADE,
    container_id TEXT NOT NULL CHECK (length(container_id) BETWEEN 1 AND 128),
    ts TIMESTAMPTZ NOT NULL,
    compose_project TEXT NULL CHECK (compose_project IS NULL OR length(compose_project) BETWEEN 1 AND 256),
    cpu_percent DOUBLE PRECISION NOT NULL,
    mem_bytes BIGINT NOT NULL,
    net_rx_bytes BIGINT NOT NULL,
    net_tx_bytes BIGINT NOT NULL,
    blk_read_bytes BIGINT NOT NULL,
    blk_write_bytes BIGINT NOT NULL,
    PRIMARY KEY (host_id, container_id, ts),
    CONSTRAINT stats_samples_non_negative CHECK (
        cpu_percent >= 0 AND mem_bytes >= 0 AND net_rx_bytes >= 0 AND net_tx_bytes >= 0
        AND blk_read_bytes >= 0 AND blk_write_bytes >= 0)
);

-- The retention purge deletes by age across all hosts.
CREATE INDEX stats_samples_ts_idx ON stats_samples (ts);

ALTER TABLE retention_settings
    ADD COLUMN stats_days INT NOT NULL DEFAULT 7 CHECK (stats_days BETWEEN 1 AND 3650);
