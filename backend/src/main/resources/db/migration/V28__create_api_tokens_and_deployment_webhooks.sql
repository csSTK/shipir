-- Personal API tokens. Only the SHA-256 hex digest of the token is stored (64 lowercase hex chars), never the token.
-- prefix is a short, non-secret part of the token shown in the UI to tell tokens apart.
CREATE TABLE api_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL
        CONSTRAINT api_tokens_name_check CHECK (char_length(btrim(name)) BETWEEN 1 AND 100),
    token_hash VARCHAR(64) NOT NULL
        CONSTRAINT api_tokens_token_hash_check CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    prefix VARCHAR(16) NOT NULL
        CONSTRAINT api_tokens_prefix_check CHECK (char_length(prefix) BETWEEN 4 AND 16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NULL,
    last_used_at TIMESTAMPTZ NULL,
    CONSTRAINT api_tokens_expires_after_created_check CHECK (expires_at IS NULL OR expires_at > created_at),
    CONSTRAINT uq_api_tokens_token_hash UNIQUE (token_hash)
);
CREATE INDEX idx_api_tokens_user_id ON api_tokens(user_id);

-- Redeploy webhook: one per deployment. Only the SHA-256 hex digest of the token is stored.
-- rotated_at is set when the token is replaced; NULL means the first token is still in use.
CREATE TABLE deployment_webhooks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deployment_id UUID NOT NULL REFERENCES deployments(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL
        CONSTRAINT deployment_webhooks_token_hash_check CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    rotated_at TIMESTAMPTZ NULL,
    CONSTRAINT deployment_webhooks_rotated_after_created_check CHECK (rotated_at IS NULL OR rotated_at >= created_at),
    CONSTRAINT uq_deployment_webhooks_deployment UNIQUE (deployment_id),
    CONSTRAINT uq_deployment_webhooks_token_hash UNIQUE (token_hash)
);
