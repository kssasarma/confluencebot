-- An append-only trail of admin actions on user accounts (create, resend welcome email, delete),
-- so onboarding/offboarding activity can be reported on — the read side an analytics tool would
-- pull from is GET /api/admin/audit.
--
-- target_user_id is nulled rather than cascaded on delete: the whole point of the DELETED event is
-- to survive the account it names, so the row must outlive it.
CREATE TABLE admin_user_events (
    id             BIGSERIAL    PRIMARY KEY,
    event_type     VARCHAR(20)  NOT NULL,
    admin_email    VARCHAR(255) NOT NULL,
    target_user_id BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    target_email   VARCHAR(255) NOT NULL,
    roles          VARCHAR(255),
    email_sent     BOOLEAN,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_admin_user_events_target_user_id ON admin_user_events(target_user_id);
CREATE INDEX idx_admin_user_events_created_at ON admin_user_events(created_at DESC);
