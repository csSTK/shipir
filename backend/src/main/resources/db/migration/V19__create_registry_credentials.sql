-- Credentials (username + password or token) for private image registries used by image pulls.
-- The secret is stored only as ciphertext (SecretCipher); secret_hint keeps a non-sensitive display value.
-- One credential per registry host, so a pull can pick its credential by the image's registry.
CREATE TABLE registry_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL CONSTRAINT registry_credentials_name_key UNIQUE,
    registry_host VARCHAR(255) NOT NULL CONSTRAINT registry_credentials_registry_host_key UNIQUE,
    username VARCHAR(255) NOT NULL,
    secret_ciphertext BYTEA NOT NULL,
    secret_hint VARCHAR(32) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
