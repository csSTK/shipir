-- TOTP second factor. The secret is stored encrypted via SecretCipher (never plain text).
-- Enabled means a verified secret exists: enabled_at can only be set together with a secret.
ALTER TABLE users
    ADD COLUMN totp_secret_ciphertext BYTEA NULL,
    ADD COLUMN totp_enabled_at TIMESTAMPTZ NULL,
    -- Time step (30 s counter) of the last accepted code, for replay protection.
    ADD COLUMN totp_last_used_step BIGINT NULL CHECK (totp_last_used_step >= 0),
    ADD CONSTRAINT users_totp_enabled_requires_secret_check
        CHECK (totp_enabled_at IS NULL OR totp_secret_ciphertext IS NOT NULL);

-- One-time recovery codes, stored as hashes only. used_at marks a consumed code.
CREATE TABLE user_recovery_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash TEXT NOT NULL,
    used_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_recovery_codes_user_id ON user_recovery_codes(user_id);

-- Singleton setting (same pattern as retention_settings): the CHECK pins the only allowed id to 1.
CREATE TABLE security_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    require_2fa_for_admins BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID NULL REFERENCES users(id) ON DELETE SET NULL
);

-- Configuration, not test data: two-factor is not required for admins until an ADMIN turns it on.
INSERT INTO security_settings (id, require_2fa_for_admins) VALUES (1, FALSE);
