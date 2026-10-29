-- deployed_at says a deploy or rollback was started with this version (it is immutable from then on); it does not say the
-- run worked. deploy_succeeded_at is set when `docker compose up` for the version finished successfully, so the version list
-- can tell the latest successfully deployed version ("current") from a failed attempt.
ALTER TABLE deployment_versions ADD COLUMN deploy_succeeded_at TIMESTAMPTZ;

-- History before this column cannot tell failed from successful runs: treat every started deploy as successful, except
-- versions of a deployment whose status is ERROR and which are its newest started deploy.
UPDATE deployment_versions v SET deploy_succeeded_at = v.deployed_at
 WHERE v.deployed_at IS NOT NULL
   AND NOT (v.id = (SELECT id FROM deployment_versions w WHERE w.deployment_id = v.deployment_id
                     AND w.deployed_at IS NOT NULL ORDER BY w.created_at DESC LIMIT 1)
            AND (SELECT d.status FROM deployments d WHERE d.id = v.deployment_id) = 'ERROR');
