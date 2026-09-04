-- A user's own display name: self-service, and separate from the email they sign in with. Email
-- identifies the account and never changes; name is who the person is to everyone else, and they
-- set it themselves. Nullable because existing accounts predate this column — the app gates on
-- name IS NULL to prompt for one, the same way it already gates on must_change_password.
ALTER TABLE users ADD COLUMN name VARCHAR(255);
