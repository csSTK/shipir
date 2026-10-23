-- Credentials (username + personal access token) for private Git repositories used as stack sources.
-- The token is stored only as ciphertext (SecretCipher); token_hint keeps a non-sensitive display value.
CREATE TABLE git_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL CONSTRAINT git_credentials_name_key UNIQUE,
    username VARCHAR(255) NOT NULL,
    token_ciphertext BYTEA NOT NULL,
    token_hint VARCHAR(32) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
