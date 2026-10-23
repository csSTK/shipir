-- A version that was handed to a deploy or rollback is immutable; NULL = still an editable draft.
ALTER TABLE stack_versions ADD COLUMN deployed_at TIMESTAMPTZ;
