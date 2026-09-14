package com.kssasarma.confluencebot.rag.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Native SQL repository for hybrid (dense + lexical) retrieval from the confluence_chunks table.
 *
 * Dense path:  cosine ANN via the HNSW index on the embedding column.
 * Lexical path: GIN full-text index on to_tsvector('english', content).
 *
 * Both paths return the raw embedding text so the caller (HybridSearchService) can recompute
 * cosine similarity uniformly across candidates from both paths — a lexical-only hit would have
 * no meaningful cosine score otherwise and would be silently penalised in MMR re-ranking.
 */
@Repository
public class ChunkSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(ChunkSearchRepository.class);

    private static final String DENSE_QUERY = """
            SELECT id::text            AS chunk_id,
                   content,
                   metadata::text      AS metadata_json,
                   embedding::text     AS embedding_text
            FROM   confluence_chunks
            ORDER  BY embedding <=> CAST(? AS vector)
            LIMIT  ?
            """;

    private static final String DENSE_QUERY_BY_SPACE = """
            SELECT id::text            AS chunk_id,
                   content,
                   metadata::text      AS metadata_json,
                   embedding::text     AS embedding_text
            FROM   confluence_chunks
            WHERE  metadata->>'space_key' = ?
            ORDER  BY embedding <=> CAST(? AS vector)
            LIMIT  ?
            """;

    private static final String LEXICAL_QUERY = """
            SELECT id::text            AS chunk_id,
                   content,
                   metadata::text      AS metadata_json,
                   embedding::text     AS embedding_text
            FROM   confluence_chunks
            WHERE  to_tsvector('english', content) @@ plainto_tsquery('english', ?)
            ORDER  BY ts_rank(to_tsvector('english', content),
                              plainto_tsquery('english', ?)) DESC
            LIMIT  ?
            """;

    private static final String LEXICAL_QUERY_BY_SPACE = """
            SELECT id::text            AS chunk_id,
                   content,
                   metadata::text      AS metadata_json,
                   embedding::text     AS embedding_text
            FROM   confluence_chunks
            WHERE  to_tsvector('english', content) @@ plainto_tsquery('english', ?)
            AND    metadata->>'space_key' = ?
            ORDER  BY ts_rank(to_tsvector('english', content),
                              plainto_tsquery('english', ?)) DESC
            LIMIT  ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ChunkSearchRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Dense ANN search using cosine distance on the HNSW index.
     *
     * @param embeddingStr vector string in pgvector format, e.g. {@code [0.1,0.2,...]}
     * @param limit        candidate pool size (typically larger than the final top-K)
     * @param spaceKey     restricts the search to this Confluence space's chunks; {@code null} (or
     *                     blank) searches every space, using the HNSW index unfiltered
     */
    public List<RawCandidate> findTopNDense(String embeddingStr, int limit, String spaceKey) {
        try {
            return (spaceKey == null || spaceKey.isBlank())
                    ? jdbc.query(DENSE_QUERY, RAW_CANDIDATE_MAPPER, embeddingStr, limit)
                    : jdbc.query(DENSE_QUERY_BY_SPACE, RAW_CANDIDATE_MAPPER, spaceKey, embeddingStr, limit);
        } catch (Exception e) {
            log.error("Dense search failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private static final String TABLE_DENSE_QUERY = """
            SELECT id::text            AS chunk_id,
                   content,
                   metadata::text      AS metadata_json,
                   embedding::text     AS embedding_text
            FROM   confluence_chunks
            WHERE  metadata->>'chunk_type' = 'TABLE'
            ORDER  BY embedding <=> CAST(? AS vector)
            LIMIT  ?
            """;

    private static final String TABLE_DENSE_QUERY_BY_SPACE = """
            SELECT id::text            AS chunk_id,
                   content,
                   metadata::text      AS metadata_json,
                   embedding::text     AS embedding_text
            FROM   confluence_chunks
            WHERE  metadata->>'chunk_type' = 'TABLE'
            AND    metadata->>'space_key'  = ?
            ORDER  BY embedding <=> CAST(? AS vector)
            LIMIT  ?
            """;

    /**
     * Dense ANN search restricted to TABLE chunks. Used as a dedicated table retrieval pass so
     * table chunks are never entirely absent from the candidate pool — bare cell text routinely
     * scores below prose in joint retrieval, meaning the sibling expansion in
     * {@code HybridSearchService} never triggers for tables that ranked below the pool cutoff.
     */
    public List<RawCandidate> findTopNDenseTable(String embeddingStr, int limit, String spaceKey) {
        try {
            return (spaceKey == null || spaceKey.isBlank())
                    ? jdbc.query(TABLE_DENSE_QUERY, RAW_CANDIDATE_MAPPER, embeddingStr, limit)
                    : jdbc.query(TABLE_DENSE_QUERY_BY_SPACE, RAW_CANDIDATE_MAPPER, spaceKey, embeddingStr, limit);
        } catch (Exception e) {
            log.warn("Table-only dense search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static final String TABLE_SIBLINGS_QUERY = """
            SELECT id::text            AS chunk_id,
                   content,
                   metadata::text      AS metadata_json,
                   embedding::text     AS embedding_text
            FROM   confluence_chunks
            WHERE  metadata->>'page_id'         = ?
            AND    metadata->>'section_heading' = ?
            AND    metadata->>'chunk_type'      = 'TABLE'
            """;

    private static final String TABLE_SIBLINGS_NO_HEADING_QUERY = """
            SELECT id::text            AS chunk_id,
                   content,
                   metadata::text      AS metadata_json,
                   embedding::text     AS embedding_text
            FROM   confluence_chunks
            WHERE  metadata->>'page_id'    = ?
            AND    (metadata->>'section_heading' IS NULL OR metadata->>'section_heading' = '')
            AND    metadata->>'chunk_type' = 'TABLE'
            """;

    /**
     * Fetches every TABLE chunk that belongs to the same logical table as the given
     * (pageId, sectionHeading) pair. When sectionHeading is blank — meaning the table sits
     * directly under the page with no section heading stored — falls back to matching by
     * pageId alone among headingless TABLE chunks, which is the correct group boundary in
     * that case (all row-batch splits share the same blank heading).
     */
    public List<RawCandidate> findTableSiblings(String pageId, String sectionHeading) {
        if (pageId == null || pageId.isBlank()) return Collections.emptyList();
        try {
            return (sectionHeading == null || sectionHeading.isBlank())
                    ? jdbc.query(TABLE_SIBLINGS_NO_HEADING_QUERY, RAW_CANDIDATE_MAPPER, pageId)
                    : jdbc.query(TABLE_SIBLINGS_QUERY, RAW_CANDIDATE_MAPPER, pageId, sectionHeading);
        } catch (Exception e) {
            log.warn("Table sibling fetch failed for page={} heading={}: {}", pageId, sectionHeading, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Lexical full-text search using the GIN tsvector index (added in V4 migration).
     *
     * @param spaceKey restricts the search to this Confluence space's chunks; {@code null} (or
     *                 blank) searches every space.
     */
    public List<RawCandidate> findTopNLexical(String query, int limit, String spaceKey) {
        try {
            return (spaceKey == null || spaceKey.isBlank())
                    ? jdbc.query(LEXICAL_QUERY, RAW_CANDIDATE_MAPPER, query, query, limit)
                    : jdbc.query(LEXICAL_QUERY_BY_SPACE, RAW_CANDIDATE_MAPPER, query, spaceKey, query, limit);
        } catch (Exception e) {
            log.warn("Lexical search failed (index may not exist yet): {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static final RowMapper<RawCandidate> RAW_CANDIDATE_MAPPER = ChunkSearchRepository::mapRawCandidate;

    private static RawCandidate mapRawCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new RawCandidate(
                rs.getString("chunk_id"),
                rs.getString("content"),
                rs.getString("metadata_json"),
                rs.getString("embedding_text"));
    }

    /** Hydrates a {@link RawCandidate} into a {@link RetrievedChunk} by parsing its JSON metadata. */
    public RetrievedChunk toRetrievedChunk(RawCandidate raw, float[] queryEmbedding) {
        Map<String, Object> meta = parseMetadata(raw.metadataJson());
        float[] embedding = parseEmbedding(raw.embeddingText());
        double similarity = cosineSimilarity(queryEmbedding, embedding);
        // TABLE chunks store the focused retrieval text as the content column (for embedding and
        // lexical search) and the full pipe-delimited table in full_table_content metadata (for
        // LLM display). Fall back to raw content for non-TABLE chunks and pre-fix TABLE chunks.
        String fullContent = string(meta, "full_table_content");
        String content = fullContent.isBlank() ? raw.content() : fullContent;
        return RetrievedChunk.builder()
            .chunkId(raw.chunkId())
            .content(content)
            .pageId(string(meta, "page_id"))
            .title(string(meta, "title"))
            .pageUrl(string(meta, "page_url"))
            .spaceKey(string(meta, "space_key"))
            .sectionHeading(string(meta, "section_heading"))
            .chunkType(string(meta, "chunk_type", "TEXT"))
            .similarity(similarity)
            .embedding(embedding)
            .build();
    }

    public record RawCandidate(String chunkId, String content,
                                String metadataJson, String embeddingText) {}

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse chunk metadata JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    static float[] parseEmbedding(String text) {
        if (text == null || text.isBlank()) return new float[0];
        String s = text.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]"))   s = s.substring(0, s.length() - 1);
        String[] parts = s.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Float.parseFloat(parts[i].trim()); }
            catch (NumberFormatException ignored) { result[i] = 0f; }
        }
        return result;
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot  += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static String string(Map<String, Object> meta, String key) {
        return string(meta, key, "");
    }

    private static String string(Map<String, Object> meta, String key, String defaultVal) {
        Object v = meta.get(key);
        return (v instanceof String s && !s.isBlank()) ? s : defaultVal;
    }
}
