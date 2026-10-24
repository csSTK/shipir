-- Versions created by adopting an existing Compose project (docker compose project not started by Shipir).
ALTER TABLE deployment_versions DROP CONSTRAINT deployment_versions_source_check;
ALTER TABLE deployment_versions
    ADD CONSTRAINT deployment_versions_source_check CHECK (source IN ('MANUAL', 'GIT', 'ROLLBACK', 'ADOPTED'));
