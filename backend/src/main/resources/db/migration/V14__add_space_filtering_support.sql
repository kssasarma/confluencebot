-- Space name, captured at ingestion time so the space picker (GET /api/spaces) can show a
-- human-readable name instead of just the key. Nullable: pages ingested before this migration
-- have no name recorded until their space is re-ingested.
ALTER TABLE confluence_pages ADD COLUMN space_name TEXT;

-- Functional index so the space-scoped dense/lexical retrieval queries (ChunkSearchRepository)
-- added for per-space chat search can filter confluence_chunks by space_key without a sequential
-- scan, mirroring the existing page_id and chunk_type functional indexes from V2/V4.
CREATE INDEX IF NOT EXISTS confluence_chunks_space_key_idx
    ON confluence_chunks ((metadata->>'space_key'));
