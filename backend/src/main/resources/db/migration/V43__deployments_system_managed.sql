-- A system-managed deployment (the managed reverse proxy) is operated through its own feature (Domains), not through the
-- generic deployment endpoints: its env holds ADMIN-only secrets (ACME DNS credentials). Non-ADMIN callers get 403 on
-- the generic endpoints for it, see DeploymentService.
ALTER TABLE deployments ADD COLUMN system_managed BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill the proxy deployments created before the flag existed: the one a proxy instance points at, and (fail closed)
-- any deployment carrying the reserved proxy name.
UPDATE deployments
SET system_managed = TRUE
WHERE name = 'shipir-proxy'
   OR id IN (SELECT deployment_id FROM proxy_instances WHERE deployment_id IS NOT NULL);
