-- Full-text search index on chunk content for hybrid (dense + lexical) RAG retrieval.
-- Used by ChunkSearchRepository.findTopNLexical — plainto_tsquery matches against this GIN index.
-- A functional GIN index is used (not a generated column) so Flyway manages all DDL without
-- touching Spring AI's existing table structure.
CREATE INDEX IF NOT EXISTS confluence_chunks_content_fts_idx
    ON confluence_chunks
    USING GIN (to_tsvector('english', content));

-- Functional index on metadata chunk_type for future filtered queries
CREATE INDEX IF NOT EXISTS confluence_chunks_chunk_type_idx
    ON confluence_chunks ((metadata->>'chunk_type'));
