CREATE TABLE stacks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    host_id UUID NOT NULL REFERENCES docker_hosts(id) ON DELETE CASCADE,
    compose_file_path VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'STOPPED',
    created_by UUID NOT NULL REFERENCES users(id),
    last_deployed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (host_id, name)
);
