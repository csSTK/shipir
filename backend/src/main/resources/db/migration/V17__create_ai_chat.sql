-- AI assistant settings: singleton row (the CHECK pins the only allowed id to 1).
-- The API key is stored only as ciphertext (SecretCipher); api_key_hint keeps a non-sensitive display value.
CREATE TABLE ai_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    base_url VARCHAR(2048) NOT NULL,
    model VARCHAR(255) NOT NULL,
    api_key_ciphertext BYTEA NULL,
    api_key_hint VARCHAR(32) NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID NULL REFERENCES users(id) ON DELETE SET NULL
);

-- Configuration, not test data: the default endpoint and model, disabled until an admin enables it.
INSERT INTO ai_settings (id, base_url, model, enabled) VALUES (1, 'https://integrate.api.nvidia.com/v1', 'z-ai/glm-5.3-flash', FALSE);

-- One chat conversation per row. shared_with_admin lets the owner expose it to ADMIN users.
CREATE TABLE chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    shared_with_admin BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_sessions_user_updated ON chat_sessions(user_id, updated_at);

-- Messages of one session, ordered by seq. tool_calls holds the model's tool call requests as JSON.
-- status is only used for pending actions that wait for the user's confirmation.
CREATE TABLE chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    seq INT NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'TOOL')),
    content TEXT NULL,
    tool_calls JSONB NULL,
    tool_call_id VARCHAR(255) NULL,
    status VARCHAR(16) NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chat_messages_session_seq_key UNIQUE (session_id, seq)
);

-- Chat sessions are purged by the retention job, so the setting lives next to the other retention days.
ALTER TABLE retention_settings
    ADD COLUMN chat_session_days INT NOT NULL DEFAULT 90 CHECK (chat_session_days BETWEEN 1 AND 3650);
