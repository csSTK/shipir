-- Image auto-update: lastResult now distinguishes a rolled back failure (ROLLED_BACK) from one that could not be
-- rolled back (FAILED_NOT_ROLLED_BACK); FAILED stays for failures without a rollback attempt (pull failed) and for rows written before this change.
-- Existing APPLIED and FAILED rows stay valid. FAILED_NOT_ROLLED_BACK (22 characters) needs a wider column.
ALTER TABLE deployments ALTER COLUMN image_update_last_result TYPE VARCHAR(30);
ALTER TABLE deployments DROP CONSTRAINT deployments_image_update_last_result_check;
ALTER TABLE deployments ADD CONSTRAINT deployments_image_update_last_result_check
    CHECK (image_update_last_result IS NULL
        OR image_update_last_result IN ('APPLIED', 'FAILED', 'ROLLED_BACK', 'FAILED_NOT_ROLLED_BACK'));
