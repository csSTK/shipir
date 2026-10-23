-- Singleton row: the CHECK pins the only allowed id to 1.
CREATE TABLE retention_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    audit_log_days INT NOT NULL CHECK (audit_log_days BETWEEN 1 AND 3650),
    notification_days INT NOT NULL CHECK (notification_days BETWEEN 1 AND 3650),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID NULL REFERENCES users(id) ON DELETE SET NULL
);

-- Configuration, not test data: the default retention rules.
INSERT INTO retention_settings (id, audit_log_days, notification_days) VALUES (1, 365, 30);
