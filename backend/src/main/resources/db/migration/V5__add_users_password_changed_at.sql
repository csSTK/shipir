-- NULL means the user still has to complete the first-start setup (forced password change).
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ NULL;
