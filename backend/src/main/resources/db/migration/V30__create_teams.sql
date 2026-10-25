-- Teams: named groups of users that can hold permissions as one subject.
CREATE TABLE teams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT teams_name_key UNIQUE (name)
);

-- Membership: a user belongs to a team at most once. Deleting either side removes the membership.
CREATE TABLE team_members (
    team_id UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT team_members_pkey PRIMARY KEY (team_id, user_id)
);
-- The primary key covers lookups by team; this one covers "which teams is this user in".
CREATE INDEX idx_team_members_user_id ON team_members(user_id);

-- A grant now belongs to exactly one subject: a user or a team.
-- Existing rows all have user_id set, so relaxing NOT NULL changes no data.
ALTER TABLE permissions
    ALTER COLUMN user_id DROP NOT NULL,
    ADD COLUMN team_id UUID NULL REFERENCES teams(id) ON DELETE CASCADE,
    ADD CONSTRAINT permissions_one_subject_check CHECK ((user_id IS NULL) <> (team_id IS NULL)),
    ADD CONSTRAINT permissions_team_host_key UNIQUE (team_id, host_id),
    ADD CONSTRAINT permissions_team_deployment_key UNIQUE (team_id, deployment_id);

-- Cascades from teams look up their grants by team_id.
CREATE INDEX idx_permissions_team_id ON permissions(team_id);
