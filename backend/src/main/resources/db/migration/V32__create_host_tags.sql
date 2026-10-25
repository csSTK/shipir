-- Environment tags (e.g. prod, staging): free-form labels on a Docker host, used for grouping and filtering.
-- Tags are stored normalised (trimmed, lowercase). The service caps a host at 10 tags; the database only enforces
-- the shape of each tag and that a host carries a tag at most once.
CREATE TABLE host_tags (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    host_id UUID NOT NULL REFERENCES docker_hosts(id) ON DELETE CASCADE,
    tag VARCHAR(24) NOT NULL CONSTRAINT host_tags_tag_check CHECK (tag ~ '^[a-z0-9][a-z0-9-]{0,23}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_host_tags_host_tag UNIQUE (host_id, tag)
);
