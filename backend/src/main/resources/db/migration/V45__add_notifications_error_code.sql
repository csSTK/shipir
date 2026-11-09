-- Machine-readable reason of an IMAGE_UPDATE_FAILED notification (e.g. UNHEALTHY_ROLLED_BACK), so the UI can show
-- a localized reason and rollback outcome. Null for all other notification types.
ALTER TABLE notifications ADD COLUMN error_code VARCHAR(64) NULL;
