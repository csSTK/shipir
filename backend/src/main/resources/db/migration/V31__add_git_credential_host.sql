-- A Git credential is bound to the host its token may be sent to (e.g. github.com, optionally host:port).
-- Without the binding a lower-privileged user could pair the credential with an attacker-chosen repository URL.
-- NULL = unbound: such a credential is refused for every URL until an ADMIN sets the host.
ALTER TABLE git_credentials ADD COLUMN host VARCHAR(255) NULL;

-- Backfill ONLY when every deployment using the credential points at the same single host. A credential used with
-- several hosts stays NULL: an attacker-created deployment must not be able to choose the host the token gets bound to.
UPDATE git_credentials c
SET host = u.host
FROM (
    SELECT git_credential_id AS id,
           min(h) AS host
    FROM (
        SELECT git_credential_id,
               lower(rtrim(substring(git_repo_url FROM '^[A-Za-z][A-Za-z0-9+.-]*://([^/:?#@]+)'), '.')) AS h
        FROM deployments
        WHERE git_credential_id IS NOT NULL AND git_repo_url IS NOT NULL
    ) d
    GROUP BY git_credential_id
    HAVING count(DISTINCT h) = 1 AND bool_and(h IS NOT NULL)
) u
WHERE c.id = u.id;
