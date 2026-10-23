-- Stacks can come from a file written in Shipir (FILE) or from a Git repository (GIT, optionally auto-updated).
ALTER TABLE stacks
    ADD COLUMN source_type VARCHAR(10) NOT NULL DEFAULT 'FILE'
        CONSTRAINT stacks_source_type_check CHECK (source_type IN ('FILE', 'GIT')),
    ADD COLUMN git_repo_url VARCHAR(500) NULL,
    ADD COLUMN git_ref VARCHAR(255) NULL,
    ADD COLUMN git_compose_path VARCHAR(500) NULL,
    ADD COLUMN git_credential_id UUID NULL REFERENCES git_credentials(id) ON DELETE RESTRICT,
    ADD COLUMN auto_update_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN auto_update_interval_seconds INTEGER NULL
        CONSTRAINT stacks_auto_update_interval_check CHECK (auto_update_interval_seconds >= 60),
    ADD COLUMN last_commit_sha VARCHAR(64) NULL,
    ADD COLUMN current_version_id UUID NULL REFERENCES stack_versions(id) ON DELETE SET NULL;

-- Git columns are present exactly when the stack is Git-sourced. The credential is optional (public repos).
ALTER TABLE stacks
    ADD CONSTRAINT stacks_git_columns_check CHECK (
        (source_type = 'GIT' AND git_repo_url IS NOT NULL AND git_ref IS NOT NULL AND git_compose_path IS NOT NULL)
        OR (source_type = 'FILE' AND git_repo_url IS NULL AND git_ref IS NULL AND git_compose_path IS NULL
            AND git_credential_id IS NULL AND auto_update_enabled = FALSE
            AND auto_update_interval_seconds IS NULL AND last_commit_sha IS NULL)
    );

-- env_ciphertext holds the encrypted .env content of a version (may contain secrets).
ALTER TABLE stack_versions
    ADD COLUMN env_ciphertext BYTEA NULL,
    ADD COLUMN commit_sha VARCHAR(64) NULL,
    ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'MANUAL'
        CONSTRAINT stack_versions_source_check CHECK (source IN ('MANUAL', 'GIT', 'ROLLBACK'));

CREATE INDEX idx_stacks_git_credential_id ON stacks(git_credential_id);
