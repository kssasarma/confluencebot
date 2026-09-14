CREATE TABLE ingestion_schedules (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    space_key       VARCHAR(50)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    interval_hours  INT          NOT NULL DEFAULT 24,
    force           BOOLEAN      NOT NULL DEFAULT false,
    last_run_at     TIMESTAMPTZ,
    next_run_at     TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(255) NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    CONSTRAINT uq_ingestion_schedules_space_key UNIQUE (space_key),
    CONSTRAINT chk_ingestion_schedules_interval CHECK (interval_hours >= 1 AND interval_hours <= 8760)
);

-- Partial index: only enabled schedules need to be scanned by the scheduler
CREATE INDEX idx_ingestion_schedules_due
    ON ingestion_schedules (next_run_at)
    WHERE enabled = true;
