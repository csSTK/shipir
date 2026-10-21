CREATE TABLE docker_hosts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    connection_type VARCHAR(20) NOT NULL,   -- LOCAL / TCP
    host VARCHAR(255),
    port INTEGER,
    tls_cert_ref VARCHAR(255),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
