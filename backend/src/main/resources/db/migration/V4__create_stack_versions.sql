CREATE TABLE stack_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stack_id UUID NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,
    yaml_content TEXT NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_stack_versions_stack_id ON stack_versions(stack_id);
