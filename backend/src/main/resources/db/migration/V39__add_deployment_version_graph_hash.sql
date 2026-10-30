-- SHA-256 (hex) of the compose graph in the deployment directory (compose file, .env, include/extends/env_file targets,
-- Git checkout) at the moment an ADMIN deployed the version. System runs (webhook, auto-updates) keep the unrestricted
-- profile only while the graph on disk still hashes to this value. NULL = not ADMIN-approved (or deployed before this column).
ALTER TABLE deployment_versions ADD COLUMN graph_hash VARCHAR(64);
ALTER TABLE deployment_versions ADD CONSTRAINT deployment_versions_graph_hash_check
    CHECK (graph_hash IS NULL OR graph_hash ~ '^[0-9a-f]{64}$');
