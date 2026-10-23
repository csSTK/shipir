-- State of the Git polling auto-update: when the remote was last checked and the error code of the last failed check.
ALTER TABLE stacks
    ADD COLUMN last_polled_at TIMESTAMPTZ NULL,
    ADD COLUMN last_poll_error VARCHAR(50) NULL;
