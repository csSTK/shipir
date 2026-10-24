-- Per-user grants on one host or one deployment. VIEW reads, OPERATE also runs lifecycle actions.
-- Exactly one of host_id / deployment_id is set, so a grant never applies to both at once.
-- The unique constraints are per target column, so a user cannot hold two grants on the same target.
CREATE TABLE permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    host_id UUID NULL REFERENCES docker_hosts(id) ON DELETE CASCADE,
    deployment_id UUID NULL REFERENCES deployments(id) ON DELETE CASCADE,
    level VARCHAR(16) NOT NULL CHECK (level IN ('VIEW', 'OPERATE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT permissions_one_target_check CHECK ((host_id IS NULL) <> (deployment_id IS NULL)),
    CONSTRAINT permissions_user_host_key UNIQUE (user_id, host_id),
    CONSTRAINT permissions_user_deployment_key UNIQUE (user_id, deployment_id)
);

-- Cascades from hosts and deployments look up their grants by these columns.
CREATE INDEX idx_permissions_host_id ON permissions(host_id);
CREATE INDEX idx_permissions_deployment_id ON permissions(deployment_id);
