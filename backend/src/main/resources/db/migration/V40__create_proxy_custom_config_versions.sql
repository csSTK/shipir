-- Backups of the user-editable custom proxy config (Advanced mode). Every write of proxy_instances.custom_config
-- first stores the content it replaces here, so any earlier state can be restored; the service keeps the newest N rows
-- per proxy. created_by is the user name at the time of the change (kept as text so history survives user deletion).
CREATE TABLE proxy_custom_config_versions
(
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proxy_instance_id UUID         NOT NULL REFERENCES proxy_instances (id) ON DELETE CASCADE,
    content           TEXT         NOT NULL,
    created_by        VARCHAR(255) NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_proxy_custom_config_versions_instance_created
    ON proxy_custom_config_versions (proxy_instance_id, created_at DESC);
