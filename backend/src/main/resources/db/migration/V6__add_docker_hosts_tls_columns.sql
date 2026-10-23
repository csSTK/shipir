-- TLS material is stored as ciphertext (IV included), encrypted by the application before it reaches the database.
-- tls_cert_ref (a plain reference) is replaced by the encrypted columns.
ALTER TABLE docker_hosts DROP COLUMN tls_cert_ref;

ALTER TABLE docker_hosts
    ADD COLUMN tls_ca_encrypted   BYTEA NULL,
    ADD COLUMN tls_cert_encrypted BYTEA NULL,
    ADD COLUMN tls_key_encrypted  BYTEA NULL;

ALTER TABLE docker_hosts
    ADD CONSTRAINT docker_hosts_connection_type_check
    CHECK (connection_type IN ('LOCAL', 'TCP'));

-- LOCAL (Unix socket) has no host, no port and no TLS material. TCP needs a host and a valid port.
-- Port checks use IS NOT NULL explicitly, because a CHECK that evaluates to NULL passes.
ALTER TABLE docker_hosts
    ADD CONSTRAINT docker_hosts_connection_shape_check
    CHECK (
        (connection_type = 'LOCAL'
            AND host IS NULL
            AND port IS NULL
            AND tls_ca_encrypted IS NULL
            AND tls_cert_encrypted IS NULL
            AND tls_key_encrypted IS NULL)
        OR
        (connection_type = 'TCP'
            AND host IS NOT NULL
            AND port IS NOT NULL
            AND port BETWEEN 1 AND 65535)
    );

-- TLS material is either entirely absent or entirely present.
ALTER TABLE docker_hosts
    ADD CONSTRAINT docker_hosts_tls_all_or_none_check
    CHECK (
        (tls_ca_encrypted IS NULL AND tls_cert_encrypted IS NULL AND tls_key_encrypted IS NULL)
        OR
        (tls_ca_encrypted IS NOT NULL AND tls_cert_encrypted IS NOT NULL AND tls_key_encrypted IS NOT NULL)
    );

ALTER TABLE docker_hosts
    ADD CONSTRAINT docker_hosts_name_key UNIQUE (name);
