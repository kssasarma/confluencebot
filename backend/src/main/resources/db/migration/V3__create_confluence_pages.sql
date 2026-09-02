-- Ingestion tracking table — one row per page
-- Used to detect version changes and skip unchanged pages on re-ingestion
CREATE TABLE confluence_pages (
    page_id     VARCHAR(50)              PRIMARY KEY,
    space_key   VARCHAR(50)              NOT NULL,
    title       TEXT                     NOT NULL,
    page_url    TEXT,
    version     INTEGER                  NOT NULL,
    chunk_count INTEGER                  NOT NULL DEFAULT 0,
    ingested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX confluence_pages_space_key_idx ON confluence_pages (space_key);
