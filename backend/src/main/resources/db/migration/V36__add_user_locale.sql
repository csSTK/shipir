-- Per-user UI language. NULL = the user has not chosen one, the frontend falls back to the browser language.
ALTER TABLE users
    ADD COLUMN locale VARCHAR(8),
    ADD CONSTRAINT users_locale_check CHECK (locale IN ('en', 'de'));
