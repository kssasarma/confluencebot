CREATE TABLE ingestion_jobs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type        VARCHAR(10)  NOT NULL,
    space_key       VARCHAR(50),
    page_id         VARCHAR(50),
    force           BOOLEAN      NOT NULL DEFAULT false,
    status          VARCHAR(10)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    pages_processed INT,
    chunks_stored   INT,
    pages_skipped   INT,
    error_message   TEXT
);

CREATE INDEX idx_ingestion_jobs_status     ON ingestion_jobs (status);
CREATE INDEX idx_ingestion_jobs_created_at ON ingestion_jobs (created_at DESC);
