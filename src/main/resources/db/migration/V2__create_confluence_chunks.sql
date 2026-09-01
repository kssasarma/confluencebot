-- Spring AI's VectorStore table (custom name via application.yml table-name)
-- metadata is JSONB for index support on page_id
CREATE TABLE confluence_chunks (
    id        UUID  DEFAULT gen_random_uuid() PRIMARY KEY,
    content   TEXT  NOT NULL,
    metadata  JSONB NOT NULL DEFAULT '{}',
    embedding vector(1024)
);

-- HNSW index for ANN similarity search
-- m=16, ef_construction=64 are safe production defaults for 1024-dim vectors
CREATE INDEX confluence_chunks_embedding_hnsw_idx
    ON confluence_chunks
    USING HNSW (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Functional index so DELETE ... WHERE metadata->>'page_id' = ? uses the index
CREATE INDEX confluence_chunks_page_id_idx
    ON confluence_chunks ((metadata->>'page_id'));
