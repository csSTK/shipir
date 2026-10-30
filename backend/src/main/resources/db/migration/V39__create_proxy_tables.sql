-- Managed reverse proxy per Docker host (Traefik or Caddy), its certificates and the routes it publishes.
-- Everything hangs off docker_hosts with ON DELETE CASCADE: a host owns its proxy, certificates and routes.

-- One managed proxy per environment (host). deployment_id points at the compose deployment that runs it.
-- dns_credentials_* hold the DNS provider secret for ACME_DNS only; the secret is ciphertext, the hint is for display.
CREATE TABLE proxy_instances
(
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    host_id                   UUID         NOT NULL REFERENCES docker_hosts (id) ON DELETE CASCADE,
    deployment_id             UUID         NULL REFERENCES deployments (id) ON DELETE SET NULL,
    type                      VARCHAR(10)  NOT NULL,
    acme_email                VARCHAR(255) NULL,
    default_cert_mode         VARCHAR(20)  NOT NULL,
    dns_provider              VARCHAR(50)  NULL,
    dns_credentials_encrypted BYTEA        NULL,
    dns_credentials_hint      VARCHAR(255) NULL,
    custom_config             TEXT         NOT NULL DEFAULT '',
    custom_config_updated_at  TIMESTAMPTZ  NULL,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_proxy_instances_host UNIQUE (host_id),
    CONSTRAINT proxy_instances_type_check CHECK (type IN ('TRAEFIK', 'CADDY')),
    CONSTRAINT proxy_instances_default_cert_mode_check
        CHECK (default_cert_mode IN ('ACME_HTTP', 'ACME_DNS', 'INTERNAL', 'CUSTOM'))
);

-- Certificates uploaded per host. The private key is ciphertext only; domains are parsed from SAN/CN on upload.
CREATE TABLE proxy_certificates
(
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    host_id       UUID         NOT NULL REFERENCES docker_hosts (id) ON DELETE CASCADE,
    name          VARCHAR(100) NOT NULL,
    cert_pem      TEXT         NOT NULL,
    key_encrypted BYTEA        NOT NULL,
    domains       TEXT[]       NOT NULL DEFAULT '{}',
    not_after     TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_proxy_certificates_host_name UNIQUE (host_id, name)
);

-- One route per hostname per host. The same hostname may exist on another host.
-- basic_auth_users holds hashed credentials only (never plaintext passwords).
CREATE TABLE proxy_routes
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    host_id            UUID         NOT NULL REFERENCES docker_hosts (id) ON DELETE CASCADE,
    hostname           VARCHAR(253) NOT NULL,
    aliases            TEXT[]       NOT NULL DEFAULT '{}',
    container_name     VARCHAR(255) NOT NULL,
    target_port        INT          NOT NULL,
    backend_scheme     VARCHAR(10)  NOT NULL DEFAULT 'http',
    path_prefix        VARCHAR(255) NULL,
    entrypoint         VARCHAR(50)  NULL,
    cert_mode          VARCHAR(20)  NOT NULL,
    certificate_id     UUID         NULL REFERENCES proxy_certificates (id) ON DELETE RESTRICT,
    redirect_https     BOOLEAN      NOT NULL DEFAULT TRUE,
    basic_auth_users   JSONB        NULL,
    response_headers   JSONB        NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_proxy_routes_host_hostname UNIQUE (host_id, hostname),
    CONSTRAINT proxy_routes_hostname_check CHECK (hostname = lower(hostname)),
    CONSTRAINT proxy_routes_target_port_check CHECK (target_port BETWEEN 1 AND 65535),
    CONSTRAINT proxy_routes_backend_scheme_check CHECK (backend_scheme IN ('http', 'https', 'h2c')),
    CONSTRAINT proxy_routes_cert_mode_check CHECK (cert_mode IN ('ACME_HTTP', 'ACME_DNS', 'INTERNAL', 'CUSTOM')),
    CONSTRAINT proxy_routes_certificate_required_check CHECK (cert_mode <> 'CUSTOM' OR certificate_id IS NOT NULL)
);
