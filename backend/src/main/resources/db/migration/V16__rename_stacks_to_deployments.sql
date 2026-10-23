-- "Stack" is now called "deployment" throughout. Pure rename: data, foreign keys and ON DELETE rules are untouched.
-- The on-disk layout and compose project names do not change, so running deployments keep their containers.
ALTER TABLE stacks RENAME TO deployments;
ALTER TABLE stack_versions RENAME TO deployment_versions;
ALTER TABLE deployment_versions RENAME COLUMN stack_id TO deployment_id;

ALTER TABLE deployments RENAME CONSTRAINT stacks_pkey TO deployments_pkey;
ALTER TABLE deployments RENAME CONSTRAINT stacks_host_id_fkey TO deployments_host_id_fkey;
ALTER TABLE deployments RENAME CONSTRAINT stacks_created_by_fkey TO deployments_created_by_fkey;
ALTER TABLE deployments RENAME CONSTRAINT stacks_host_id_name_key TO deployments_host_id_name_key;
ALTER TABLE deployments RENAME CONSTRAINT stacks_git_credential_id_fkey TO deployments_git_credential_id_fkey;
ALTER TABLE deployments RENAME CONSTRAINT stacks_current_version_id_fkey TO deployments_current_version_id_fkey;
ALTER TABLE deployments RENAME CONSTRAINT stacks_source_type_check TO deployments_source_type_check;
ALTER TABLE deployments RENAME CONSTRAINT stacks_auto_update_interval_check TO deployments_auto_update_interval_check;
ALTER TABLE deployments RENAME CONSTRAINT stacks_git_columns_check TO deployments_git_columns_check;

ALTER TABLE deployment_versions RENAME CONSTRAINT stack_versions_pkey TO deployment_versions_pkey;
ALTER TABLE deployment_versions RENAME CONSTRAINT stack_versions_stack_id_fkey TO deployment_versions_deployment_id_fkey;
ALTER TABLE deployment_versions RENAME CONSTRAINT stack_versions_created_by_fkey TO deployment_versions_created_by_fkey;
ALTER TABLE deployment_versions RENAME CONSTRAINT stack_versions_source_check TO deployment_versions_source_check;

ALTER INDEX idx_stack_versions_stack_id RENAME TO idx_deployment_versions_deployment_id;
ALTER INDEX idx_stacks_git_credential_id RENAME TO idx_deployments_git_credential_id;
