CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL CHECK (type IN ('HOST_DOWN', 'HOST_UP')),
    host_id UUID NULL REFERENCES docker_hosts(id) ON DELETE SET NULL,
    host_name VARCHAR(255) NOT NULL,
    message VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at TIMESTAMPTZ NULL
);
CREATE INDEX idx_notifications_user_read_created ON notifications(user_id, read_at, created_at DESC);

CREATE TABLE notification_preferences (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL CHECK (type IN ('HOST_DOWN', 'HOST_UP')),
    in_app_enabled BOOLEAN NOT NULL,
    PRIMARY KEY (user_id, type)
);
