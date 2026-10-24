-- Adds the notification types DEPLOYMENT_FAILED, CONTAINER_CRASHED, CONTAINER_UNHEALTHY and GIT_AUTO_UPDATE_FAILED.
-- The CHECK constraints are widened by dropping and re-adding them under the same names the earlier migrations
-- produced (Postgres names inline column checks <table>_<column>_check).
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('HOST_DOWN', 'HOST_UP', 'DEPLOYMENT_FAILED', 'CONTAINER_CRASHED', 'CONTAINER_UNHEALTHY',
                    'GIT_AUTO_UPDATE_FAILED'));

ALTER TABLE notification_preferences DROP CONSTRAINT notification_preferences_type_check;
ALTER TABLE notification_preferences ADD CONSTRAINT notification_preferences_type_check
    CHECK (type IN ('HOST_DOWN', 'HOST_UP', 'DEPLOYMENT_FAILED', 'CONTAINER_CRASHED', 'CONTAINER_UNHEALTHY',
                    'GIT_AUTO_UPDATE_FAILED'));

ALTER TABLE notification_webhooks DROP CONSTRAINT notification_webhooks_event_types_check;
ALTER TABLE notification_webhooks ADD CONSTRAINT notification_webhooks_event_types_check
    CHECK (cardinality(event_types) > 0 AND event_types <@ ARRAY['HOST_DOWN', 'HOST_UP', 'DEPLOYMENT_FAILED',
        'CONTAINER_CRASHED', 'CONTAINER_UNHEALTHY', 'GIT_AUTO_UPDATE_FAILED']::VARCHAR(32)[]);

-- Context columns, all nullable: host events keep them NULL. The deployment and container names are snapshots,
-- like host_name, so the message still reads after the deployment or container is gone.
-- deployment_id is cleared when the deployment is deleted; container_id is a Docker id, not a foreign key.
ALTER TABLE notifications
    ADD COLUMN deployment_id UUID NULL REFERENCES deployments(id) ON DELETE SET NULL,
    ADD COLUMN deployment_name VARCHAR(255) NULL,
    ADD COLUMN container_id TEXT NULL,
    ADD COLUMN container_name TEXT NULL;
