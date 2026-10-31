-- Local usernames are unique ignoring case: 'Admin' next to 'admin' would look like impersonation in the audit log.
-- users.username (V1) only rejected exact duplicates, so enforce it in the database like teams (V42).

-- Existing rows that differ only by case would make the index fail: keep the oldest user's name and append the
-- first 8 characters of the id to the others ('Admin (1a2b3c4d)'), so nothing is deleted and no audit entry, token
-- or grant is lost. The base is cut so the result still fits VARCHAR(50) (11 characters of suffix).
UPDATE users u
SET username = left(u.username, 39) || ' (' || left(u.id::text, 8) || ')'
FROM (SELECT id, row_number() OVER (PARTITION BY lower(username) ORDER BY created_at, id) AS rn FROM users) d
WHERE u.id = d.id AND d.rn > 1;

-- The case-insensitive index subsumes the case-sensitive constraint from V1, so that one is dropped.
ALTER TABLE users DROP CONSTRAINT users_username_key;
CREATE UNIQUE INDEX users_username_lower_key ON users (lower(username));
