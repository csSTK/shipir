-- Scheduled image auto-update of FILE deployments (Git deployments have their own auto-update).
-- image_update_window_start/end: daily window in UTC (time of day, start inclusive, end exclusive; start after end
-- wraps over midnight). Both NULL = no window, any time. Equal bounds would be an empty window and are rejected.
-- image_update_last_attempt_at/result: last automatic attempt; an attempt is made at most once per update check result.
ALTER TABLE deployments
    ADD COLUMN image_auto_update BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN image_update_window_start TIME NULL,
    ADD COLUMN image_update_window_end TIME NULL,
    ADD COLUMN image_update_last_attempt_at TIMESTAMPTZ NULL,
    ADD COLUMN image_update_last_result VARCHAR(20) NULL;

ALTER TABLE deployments ADD CONSTRAINT deployments_image_update_window_check
    CHECK ((image_update_window_start IS NULL AND image_update_window_end IS NULL)
        OR (image_update_window_start IS NOT NULL AND image_update_window_end IS NOT NULL
            AND image_update_window_start <> image_update_window_end));
ALTER TABLE deployments ADD CONSTRAINT deployments_image_update_last_result_check
    CHECK (image_update_last_result IS NULL OR image_update_last_result IN ('APPLIED', 'FAILED'));

-- Notification types IMAGE_UPDATE_APPLIED and IMAGE_UPDATE_FAILED (constraint names as in V23).
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('HOST_DOWN', 'HOST_UP', 'DEPLOYMENT_FAILED', 'CONTAINER_CRASHED', 'CONTAINER_UNHEALTHY',
                    'GIT_AUTO_UPDATE_FAILED', 'IMAGE_UPDATE_APPLIED', 'IMAGE_UPDATE_FAILED'));

ALTER TABLE notification_preferences DROP CONSTRAINT notification_preferences_type_check;
ALTER TABLE notification_preferences ADD CONSTRAINT notification_preferences_type_check
    CHECK (type IN ('HOST_DOWN', 'HOST_UP', 'DEPLOYMENT_FAILED', 'CONTAINER_CRASHED', 'CONTAINER_UNHEALTHY',
                    'GIT_AUTO_UPDATE_FAILED', 'IMAGE_UPDATE_APPLIED', 'IMAGE_UPDATE_FAILED'));

ALTER TABLE notification_webhooks DROP CONSTRAINT notification_webhooks_event_types_check;
ALTER TABLE notification_webhooks ADD CONSTRAINT notification_webhooks_event_types_check
    CHECK (cardinality(event_types) > 0 AND event_types <@ ARRAY['HOST_DOWN', 'HOST_UP', 'DEPLOYMENT_FAILED',
        'CONTAINER_CRASHED', 'CONTAINER_UNHEALTHY', 'GIT_AUTO_UPDATE_FAILED', 'IMAGE_UPDATE_APPLIED',
        'IMAGE_UPDATE_FAILED']::VARCHAR(32)[]);
