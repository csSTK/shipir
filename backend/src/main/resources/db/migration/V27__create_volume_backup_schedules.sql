-- Recurring volume backups: one schedule per (host, volume). The scheduler stores a tar per run on disk and keeps
-- the newest keep_count archives. Backup files are not in the database.
CREATE TABLE volume_backup_schedules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    host_id UUID NOT NULL REFERENCES docker_hosts(id) ON DELETE CASCADE,
    volume_name VARCHAR(128) NOT NULL
        CHECK (volume_name ~ '^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$'),
    interval_hours INTEGER NOT NULL CHECK (interval_hours BETWEEN 1 AND 8760),
    keep_count INTEGER NOT NULL CHECK (keep_count BETWEEN 1 AND 100),
    next_run_at TIMESTAMPTZ NOT NULL,
    last_run_at TIMESTAMPTZ NULL,
    last_status VARCHAR(16) NULL CHECK (last_status IN ('SUCCESS', 'FAILED')),
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_volume_backup_schedules_host_volume UNIQUE (host_id, volume_name)
);
CREATE INDEX idx_volume_backup_schedules_next_run ON volume_backup_schedules(next_run_at);
