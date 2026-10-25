-- HTTP uptime checks of managed deployments. Notification types UPTIME_DOWN and UPTIME_UP.
-- expected_status NULL = any 2xx counts as up. last_status/consecutive_failures drive the flapping dedupe
-- (DOWN after N consecutive failures, UP after one success); only state changes notify.
CREATE TABLE uptime_checks
(
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deployment_id        UUID         NOT NULL REFERENCES deployments (id) ON DELETE CASCADE,
    name                 VARCHAR(100) NOT NULL,
    url                  VARCHAR(2048) NOT NULL,
    expected_status      INT          NULL,
    interval_seconds     INT          NOT NULL,
    timeout_seconds      INT          NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_status          VARCHAR(10)  NOT NULL DEFAULT 'UNKNOWN',
    last_checked_at      TIMESTAMPTZ  NULL,
    last_change_at       TIMESTAMPTZ  NULL,
    consecutive_failures INT          NOT NULL DEFAULT 0,
    CONSTRAINT uptime_checks_url_check CHECK (url ~ '^https?://[^@/?#]+([/?#].*)?$' AND length(url) <= 2048),
    CONSTRAINT uptime_checks_expected_status_check CHECK (expected_status IS NULL OR expected_status BETWEEN 100 AND 599),
    CONSTRAINT uptime_checks_interval_check CHECK (interval_seconds BETWEEN 30 AND 86400),
    CONSTRAINT uptime_checks_timeout_check CHECK (timeout_seconds BETWEEN 1 AND 30),
    CONSTRAINT uptime_checks_last_status_check CHECK (last_status IN ('UNKNOWN', 'UP', 'DOWN')),
    CONSTRAINT uptime_checks_failures_check CHECK (consecutive_failures >= 0)
);
CREATE INDEX idx_uptime_checks_deployment ON uptime_checks (deployment_id);

-- error_code is a fixed code (TIMEOUT, CONNECTION_REFUSED, EGRESS_DENIED, ...), never raw exception text.
CREATE TABLE uptime_results
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    check_id    UUID        NOT NULL REFERENCES uptime_checks (id) ON DELETE CASCADE,
    checked_at  TIMESTAMPTZ NOT NULL,
    ok          BOOLEAN     NOT NULL,
    status_code INT         NULL,
    latency_ms  INT         NULL,
    error_code  VARCHAR(40) NULL
);
CREATE INDEX idx_uptime_results_check_checked ON uptime_results (check_id, checked_at);
CREATE INDEX idx_uptime_results_checked ON uptime_results (checked_at);

-- Notification types UPTIME_DOWN and UPTIME_UP (constraint names as in V23/V29).
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('HOST_DOWN', 'HOST_UP', 'DEPLOYMENT_FAILED', 'CONTAINER_CRASHED', 'CONTAINER_UNHEALTHY',
                    'GIT_AUTO_UPDATE_FAILED', 'IMAGE_UPDATE_APPLIED', 'IMAGE_UPDATE_FAILED', 'UPTIME_DOWN', 'UPTIME_UP'));

ALTER TABLE notification_preferences DROP CONSTRAINT notification_preferences_type_check;
ALTER TABLE notification_preferences ADD CONSTRAINT notification_preferences_type_check
    CHECK (type IN ('HOST_DOWN', 'HOST_UP', 'DEPLOYMENT_FAILED', 'CONTAINER_CRASHED', 'CONTAINER_UNHEALTHY',
                    'GIT_AUTO_UPDATE_FAILED', 'IMAGE_UPDATE_APPLIED', 'IMAGE_UPDATE_FAILED', 'UPTIME_DOWN', 'UPTIME_UP'));

ALTER TABLE notification_webhooks DROP CONSTRAINT notification_webhooks_event_types_check;
ALTER TABLE notification_webhooks ADD CONSTRAINT notification_webhooks_event_types_check
    CHECK (cardinality(event_types) > 0 AND event_types <@ ARRAY['HOST_DOWN', 'HOST_UP', 'DEPLOYMENT_FAILED',
        'CONTAINER_CRASHED', 'CONTAINER_UNHEALTHY', 'GIT_AUTO_UPDATE_FAILED', 'IMAGE_UPDATE_APPLIED',
        'IMAGE_UPDATE_FAILED', 'UPTIME_DOWN', 'UPTIME_UP']::VARCHAR(32)[]);
