-- Single sign-on against an OAuth 2.0 / OpenID Connect provider.
--
-- Two shapes of account share this table from here on: one created in the application with a
-- password, and one provisioned on first sign-in through a directory with no password at all. The
-- password column loses its NOT NULL for the second kind — a hash of "" or a random unusable
-- string would be indistinguishable from a real credential to every query that reads it.
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

-- Whether the account was created here or by a directory. Existing rows were all created here, so
-- LOCAL is right for them and is the default for anything the admin screen creates later.
ALTER TABLE users ADD COLUMN auth_provider VARCHAR(32) NOT NULL DEFAULT 'LOCAL';

-- Which directory the subject below belongs to (app.sso.provider-id), and the subject itself.
-- Both NULL until the person signs in that way once.
--
-- The provider is stored rather than assumed because a subject is only unique within the directory
-- that issued it: two providers can both call somebody "12345", and a deployment that switches
-- provider must not seat the new directory's users in the old one's accounts. It is also what lets
-- a second provider be added later as configuration rather than as another migration.
ALTER TABLE users ADD COLUMN sso_provider_id VARCHAR(64);
ALTER TABLE users ADD COLUMN external_id VARCHAR(255);

-- One identity per provider, one account. Partial so the many rows that have never signed in
-- through a directory do not all collide on NULL.
CREATE UNIQUE INDEX ux_users_sso_identity ON users (sso_provider_id, external_id)
    WHERE sso_provider_id IS NOT NULL AND external_id IS NOT NULL;

-- The hand-off between the provider's redirect and the single-page app.
--
-- The redirect that ends a sign-in can only carry what fits in a URL, and a URL is the one place a
-- 30-day refresh token must not be: it stays in browser history and leaks through Referer. So it
-- carries one of these instead — random, single-use, and valid for about a minute — which the app
-- exchanges for a real token pair over a POST of its own.
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
