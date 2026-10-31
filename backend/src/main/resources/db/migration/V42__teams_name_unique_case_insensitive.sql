-- TeamService already treats team names as case-insensitive, but teams_name_key (V30) only rejects exact
-- duplicates, so two concurrent creates of 'Ops' and 'ops' could both succeed. Enforce it in the database.

-- Existing rows that differ only by case would make the index fail: keep the oldest team's name and append the
-- first 8 characters of the id to the others ('ops (1a2b3c4d)'), so nothing is deleted and no membership or grant
-- is lost. A numeric suffix could collide with an existing name such as 'Ops (2)'.
UPDATE teams t
SET name = t.name || ' (' || left(t.id::text, 8) || ')'
FROM (SELECT id, row_number() OVER (PARTITION BY lower(name) ORDER BY created_at, id) AS rn FROM teams) d
WHERE t.id = d.id AND d.rn > 1;

-- The case-insensitive index subsumes the case-sensitive constraint from V30, so that one is dropped.
ALTER TABLE teams DROP CONSTRAINT teams_name_key;
CREATE UNIQUE INDEX teams_name_lower_key ON teams (lower(name));
