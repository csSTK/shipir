-- NULL means the user is enabled. A disabled user cannot log in and keeps their history (audit, owned rows).
ALTER TABLE users ADD COLUMN disabled_at TIMESTAMPTZ NULL;
