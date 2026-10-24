-- Singleton OIDC configuration (single sign-on). The client secret is stored only as ciphertext (SecretCipher);
-- client_secret_hint keeps a non-sensitive display value. issuer_url and client_id stay NULL until an admin sets them.
CREATE TABLE oidc_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    issuer_url VARCHAR(2048) NULL,
    client_id VARCHAR(255) NULL,
    client_secret_ciphertext BYTEA NULL,
    client_secret_hint VARCHAR(32) NULL,
    scopes VARCHAR(512) NOT NULL DEFAULT 'openid email profile',
    groups_claim VARCHAR(255) NULL,
    role_mapping JSONB NOT NULL DEFAULT '{}'::jsonb,
    auto_provision BOOLEAN NOT NULL DEFAULT FALSE,
    local_login_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID NULL REFERENCES users(id) ON DELETE SET NULL
);

-- Configuration, not test data: no provider yet, local login stays available.
INSERT INTO oidc_settings (id) VALUES (1);

-- External identity of a user who signed in through OIDC: the issuer plus the subject claim (sub).
-- Local users keep both NULL. A subject is unique per issuer, and a user never has only half of the identity.
ALTER TABLE users
    ADD COLUMN external_issuer VARCHAR(2048) NULL,
    ADD COLUMN external_subject VARCHAR(255) NULL,
    ADD CONSTRAINT users_external_identity_check
        CHECK ((external_issuer IS NULL) = (external_subject IS NULL)),
    ADD CONSTRAINT users_external_identity_key UNIQUE (external_issuer, external_subject);
