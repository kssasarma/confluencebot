-- Single sign-on against OpenText Directory Services.
--
-- Two shapes of account share this table from here on: one created in the application with a
-- password, and one provisioned on first sign-in through OTDS with no password at all. The
-- password column loses its NOT NULL for the second kind — a hash of "" or a random unusable
-- string would be indistinguishable from a real credential to every query that reads it.
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

-- Where the account came from. Existing rows were all created here, so LOCAL is right for them
-- and is the default for anything the admin screen creates later.
ALTER TABLE users ADD COLUMN auth_provider VARCHAR(32) NOT NULL DEFAULT 'LOCAL';

-- The OTDS subject this account is linked to. NULL until the person signs in that way once.
ALTER TABLE users ADD COLUMN external_id VARCHAR(255);

-- One OTDS identity, one account. Partial so the many rows that have never seen OTDS do not all
-- collide on NULL.
CREATE UNIQUE INDEX ux_users_external_id ON users (external_id) WHERE external_id IS NOT NULL;

-- The hand-off between the OTDS redirect and the single-page app.
--
-- The redirect that ends an OTDS sign-in can only carry what fits in a URL, and a URL is the one
-- place a 30-day refresh token must not be: it stays in browser history and leaks through Referer.
-- So it carries one of these instead — random, single-use, and valid for about a minute — which
-- the app exchanges for a real token pair over a POST of its own.
CREATE TABLE sso_login_codes (
    id          BIGSERIAL    PRIMARY KEY,
    -- SHA-256 hex of the code. The code itself is never written down anywhere.
    code_hash   VARCHAR(64)  NOT NULL UNIQUE,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sso_login_codes_expires_at ON sso_login_codes (expires_at);
