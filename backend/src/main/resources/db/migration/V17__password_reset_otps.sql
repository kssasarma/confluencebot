-- One-time codes for the self-service "forgot password" flow. otp_hash is a bcrypt hash of the
-- 6-digit code, never the code itself — a 6-digit space is brute-forceable, unlike the random
-- refresh_tokens UUID, so it is hashed the same way a password is rather than compared in the
-- clear. attempts caps how many wrong guesses a single code tolerates before it is dead either
-- way. Cascades on user deletion: an OTP has no meaning once the account it resets is gone.
CREATE TABLE password_reset_otps (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    otp_hash   VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    attempts   INT          NOT NULL DEFAULT 0,
    consumed   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_password_reset_otps_user_id ON password_reset_otps(user_id);
