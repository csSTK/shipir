-- Webhook endpoints that receive notification events. The URL is stored only as ciphertext (SecretCipher),
-- because it can carry credentials in its path or query. url_hint keeps scheme and host for display.
-- event_types is a text[] column (not a join table): the set is small and always read and written together with its webhook.
CREATE TABLE notification_webhooks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL CONSTRAINT notification_webhooks_name_key UNIQUE,
    url_encrypted BYTEA NOT NULL,
    url_hint VARCHAR(255) NOT NULL,
    event_types VARCHAR(32)[] NOT NULL
        CHECK (cardinality(event_types) > 0 AND event_types <@ ARRAY['HOST_DOWN', 'HOST_UP']::VARCHAR(32)[]),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_delivery_at TIMESTAMPTZ NULL,
    last_delivery_status VARCHAR(32) NULL
);
