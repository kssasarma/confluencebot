-- A user can now hold more than one role (e.g. INGESTOR and USER together), so role membership
-- moves out of a single column on `users` and into its own table.
CREATE TABLE user_roles (
    user_id BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role    VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role)
);

INSERT INTO user_roles (user_id, role)
SELECT id, role FROM users;

ALTER TABLE users DROP COLUMN role;
