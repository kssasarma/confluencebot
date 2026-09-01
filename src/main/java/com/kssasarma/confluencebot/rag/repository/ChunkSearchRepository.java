package com.kssasarma.confluencebot.rag.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
     */
    public List<RawCandidate> findTopNDense(String embeddingStr, int limit) {
        try {
            return jdbc.query(DENSE_QUERY,
                (rs, rowNum) -> new RawCandidate(
                    rs.getString("chunk_id"),
                    rs.getString("content"),
                    rs.getString("metadata_json"),
                    rs.getString("embedding_text")),
                embeddingStr, limit);
        } catch (Exception e) {
            log.error("Dense search failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Lexical full-text search using the GIN tsvector index (added in V4 migration).
     */
    public List<RawCandidate> findTopNLexical(String query, int limit) {
        try {
            return jdbc.query(LEXICAL_QUERY,
                (rs, rowNum) -> new RawCandidate(
                    rs.getString("chunk_id"),
                    rs.getString("content"),
                    rs.getString("metadata_json"),
                    rs.getString("embedding_text")),
                query, query, limit);
        } catch (Exception e) {
            log.warn("Lexical search failed (index may not exist yet): {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Hydrates a {@link RawCandidate} into a {@link RetrievedChunk} by parsing its JSON metadata. */
    public RetrievedChunk toRetrievedChunk(RawCandidate raw, float[] queryEmbedding) {
        Map<String, Object> meta = parseMetadata(raw.metadataJson());
        float[] embedding = parseEmbedding(raw.embeddingText());
        double similarity = cosineSimilarity(queryEmbedding, embedding);
        return RetrievedChunk.builder()
            .chunkId(raw.chunkId())
            .content(raw.content())
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
