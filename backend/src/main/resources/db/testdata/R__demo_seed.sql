-- DEMO SEED. Only loaded under the Spring profile 'demo' (application-demo.yml), never in production.
--
-- The demo passwords are PUBLIC and documented in README.md (Demo section):
--   demo-admin     Demo-Admin-2026!     (ADMIN)
--   demo-operator  Demo-Operator-2026!  (OPERATOR, OPERATE on the demo environment)
--   demo-viewer    Demo-Viewer-2026!    (VIEWER, VIEW on the demo environment)
-- Anyone who can reach a demo instance can log in with them. Never reuse these on a real instance.
--
-- Repeatable migration (R__): Flyway runs it once and again only if this file changes. It is idempotent
-- (ON CONFLICT DO NOTHING) and uses fixed ids in the 0de00000-... range, which the tests use to find seed rows.
-- Password hashes are Argon2id in the app's DelegatingPasswordEncoder format ({argon2} prefix).
-- The sample deployment has a draft version (deployed_at NULL): Deploy writes the files and runs compose up.
-- No webhooks: their URL is stored encrypted and needs shipir.encryption-key, which SQL cannot produce.

-- Users: one per role. password_changed_at is set so the first-start setup gate does not force a change.
INSERT INTO users (id, username, password_hash, role, created_at, password_changed_at) VALUES
    ('0de00000-0000-4000-8000-000000000001', 'demo-admin',
     '{argon2}$argon2id$v=19$m=16384,t=2,p=1$brLa4oMMAQ1NCBCyyYMfVQ$jDPYhKULXv1tf94l6+9X5DaJTrbt3g/U3H42399dQYg',
     'ADMIN', '2026-10-01T00:00:00Z', '2026-10-01T00:00:00Z'),
    ('0de00000-0000-4000-8000-000000000002', 'demo-operator',
     '{argon2}$argon2id$v=19$m=16384,t=2,p=1$BD3WSLGtLQMX0n9vKg7Tqg$2cG01Qy6xB6VYpm49urhEAKU+sltNHT1UgtFGbGj0K0',
     'OPERATOR', '2026-10-01T00:00:00Z', '2026-10-01T00:00:00Z'),
    ('0de00000-0000-4000-8000-000000000003', 'demo-viewer',
     '{argon2}$argon2id$v=19$m=16384,t=2,p=1$nL+HiOOX6Tc6+cbVLO8/GA$O3mHt9RObwOFyTNEAKtTMveV1jakNu28fhL9zrp+730',
     'VIEWER', '2026-10-01T00:00:00Z', '2026-10-01T00:00:00Z')
ON CONFLICT DO NOTHING;

-- One LOCAL environment (the Docker socket of the container), owned by the demo admin.
INSERT INTO docker_hosts (id, name, connection_type, owner_user_id, created_at) VALUES
    ('0de00000-0000-4000-8000-000000000101', 'Local', 'LOCAL',
     '0de00000-0000-4000-8000-000000000001', '2026-10-01T00:00:00Z')
ON CONFLICT DO NOTHING;

-- Operator may operate the environment, viewer may only view it. The admin needs no grant.
INSERT INTO permissions (id, user_id, host_id, level, created_at) VALUES
    ('0de00000-0000-4000-8000-000000000401', '0de00000-0000-4000-8000-000000000002',
     '0de00000-0000-4000-8000-000000000101', 'OPERATE', '2026-10-01T00:00:00Z'),
    ('0de00000-0000-4000-8000-000000000402', '0de00000-0000-4000-8000-000000000003',
     '0de00000-0000-4000-8000-000000000101', 'VIEW', '2026-10-01T00:00:00Z')
ON CONFLICT DO NOTHING;

-- Sample deployment (STOPPED, file-based). compose_file_path follows the app's layout <hostId>/<name>/compose.yaml.
INSERT INTO deployments (id, name, host_id, compose_file_path, status, source_type, created_by, created_at) VALUES
    ('0de00000-0000-4000-8000-000000000201', 'demo-web',
     '0de00000-0000-4000-8000-000000000101',
     '0de00000-0000-4000-8000-000000000101/demo-web/compose.yaml',
     'STOPPED', 'FILE', '0de00000-0000-4000-8000-000000000001', '2026-10-01T00:00:00Z')
ON CONFLICT DO NOTHING;

INSERT INTO deployment_versions (id, deployment_id, yaml_content, source, created_by, created_at, deployed_at) VALUES
    ('0de00000-0000-4000-8000-000000000301', '0de00000-0000-4000-8000-000000000201',
     E'services:\n  web:\n    image: nginx:alpine\n    ports:\n      - "8081:80"\n    restart: unless-stopped\n',
     'MANUAL', '0de00000-0000-4000-8000-000000000001', '2026-10-01T00:00:00Z', NULL)
ON CONFLICT DO NOTHING;

UPDATE deployments SET current_version_id = '0de00000-0000-4000-8000-000000000301'
 WHERE id = '0de00000-0000-4000-8000-000000000201' AND current_version_id IS NULL;

-- Notifications: a few per user, mixed read state, so the bell and the list have something to show.
INSERT INTO notifications (id, user_id, type, host_id, host_name, message, created_at, read_at,
                           deployment_id, deployment_name) VALUES
    ('0de00000-0000-4000-8000-000000000501', '0de00000-0000-4000-8000-000000000001', 'DEPLOYMENT_FAILED',
     '0de00000-0000-4000-8000-000000000101', 'Local', 'Deployment "demo-web" failed to start',
     '2026-10-02T09:00:00Z', NULL, '0de00000-0000-4000-8000-000000000201', 'demo-web'),
    ('0de00000-0000-4000-8000-000000000502', '0de00000-0000-4000-8000-000000000001', 'HOST_UP',
     '0de00000-0000-4000-8000-000000000101', 'Local', 'Environment "Local" is back up',
     '2026-10-01T12:00:00Z', '2026-10-01T12:05:00Z', NULL, NULL),
    ('0de00000-0000-4000-8000-000000000503', '0de00000-0000-4000-8000-000000000002', 'DEPLOYMENT_FAILED',
     '0de00000-0000-4000-8000-000000000101', 'Local', 'Deployment "demo-web" failed to start',
     '2026-10-02T09:00:00Z', NULL, '0de00000-0000-4000-8000-000000000201', 'demo-web')
ON CONFLICT DO NOTHING;

-- Audit entries as the app writes them (user snapshot in username, no secrets in details).
INSERT INTO audit_log (id, occurred_at, user_id, username, action, outcome, target_type, target_id) VALUES
    ('0de00000-0000-4000-8000-000000000601', '2026-10-01T08:00:00Z',
     '0de00000-0000-4000-8000-000000000001', 'demo-admin', 'LOGIN', 'SUCCESS', 'USER', 'demo-admin'),
    ('0de00000-0000-4000-8000-000000000602', '2026-10-01T08:10:00Z',
     '0de00000-0000-4000-8000-000000000001', 'demo-admin', 'HOST_CREATE', 'SUCCESS', 'HOST',
     '0de00000-0000-4000-8000-000000000101'),
    ('0de00000-0000-4000-8000-000000000603', '2026-10-01T08:20:00Z',
     '0de00000-0000-4000-8000-000000000001', 'demo-admin', 'DEPLOYMENT_CREATE', 'SUCCESS', 'DEPLOYMENT',
     '0de00000-0000-4000-8000-000000000201'),
    ('0de00000-0000-4000-8000-000000000604', '2026-10-01T09:00:00Z', NULL, 'demo-viewer', 'LOGIN', 'FAILURE',
     'USER', 'demo-viewer')
ON CONFLICT DO NOTHING;
