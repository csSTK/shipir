-- One stored configuration per AI provider, so switching the active provider keeps the other one's settings.
-- The API key is stored only as ciphertext (SecretCipher); api_key_hint keeps a non-sensitive display value.
CREATE TABLE ai_provider_configs (
    provider VARCHAR(32) PRIMARY KEY CHECK (provider IN ('OPENAI_COMPATIBLE', 'GEMINI')),
    base_url VARCHAR(2048) NOT NULL,
    model VARCHAR(255) NOT NULL,
    api_key_ciphertext BYTEA NULL,
    api_key_hint VARCHAR(32) NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID NULL REFERENCES users(id) ON DELETE SET NULL
);

-- The existing singleton settings move to the OPENAI_COMPATIBLE config unchanged.
INSERT INTO ai_provider_configs (provider, base_url, model, api_key_ciphertext, api_key_hint, updated_at, updated_by)
SELECT 'OPENAI_COMPATIBLE', base_url, model, api_key_ciphertext, api_key_hint, updated_at, updated_by
FROM ai_settings WHERE id = 1;

-- Configuration, not test data: default Gemini endpoint and model, no key until an admin sets one.
INSERT INTO ai_provider_configs (provider, base_url, model)
VALUES ('GEMINI', 'https://generativelanguage.googleapis.com/v1beta', 'gemini-flash-latest');

-- ai_settings keeps only the global switch and the active provider.
ALTER TABLE ai_settings
    ADD COLUMN active_provider VARCHAR(32) NOT NULL DEFAULT 'OPENAI_COMPATIBLE'
        CONSTRAINT ai_settings_active_provider_check CHECK (active_provider IN ('OPENAI_COMPATIBLE', 'GEMINI'))
        CONSTRAINT ai_settings_active_provider_fkey REFERENCES ai_provider_configs(provider),
    DROP COLUMN base_url,
    DROP COLUMN model,
    DROP COLUMN api_key_ciphertext,
    DROP COLUMN api_key_hint;
