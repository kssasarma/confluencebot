package com.kssasarma.confluencebot.rag.service;

import com.kssasarma.confluencebot.rag.model.RankFusion;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import com.kssasarma.confluencebot.rag.repository.ChunkSearchRepository;
import com.kssasarma.confluencebot.rag.repository.ChunkSearchRepository.RawCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hybrid retrieval: fuses a dense (pgvector cosine-similarity) candidate pool with a lexical
 * (Postgres full-text) candidate pool via Reciprocal Rank Fusion, then narrows the fused pool
 * to the final result set via {@link ReRankingService} (MMR + optional LLM re-rank).
 *
 * Dense-only retrieval under-ranks short, specific factual queries whenever the matching
 * sentence's embedding gets diluted by surrounding unrelated text in the same chunk — lexical
 * search catches exactly that case. Fusing both signals means neither blind spot is fatal.
 *
 * Each candidate's RRF fusion score is carried through to {@link ReRankingService} (not just the
 * fused order) so a strong lexical-only hit can still win the MMR cut even when its cosine
 * similarity is mediocre — otherwise MMR silently re-collapses back to a dense-only ranking.
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final ChunkSearchRepository searchRepo;
    private final ReRankingService reRankingService;
    private final EmbeddingModel embeddingModel;

    @Value("${chat.retrieval.top-k:5}")
    private int topK;

    @Value("${chat.retrieval.candidate-pool-size:25}")
    private int candidatePoolSize;

    /** See {@link #withPinnedContent}. Comma-separated Confluence page IDs; empty disables
     *  pinning entirely. Any number of pages may be listed. */
    @Value("${chat.retrieval.pinned-page-ids:}")
    private String pinnedPageIds;

    /** Minimum cosine similarity (against the query embedding) a pinned page's table chunk must
     *  have to be injected. This is the actual relevance gate — see {@link #withPinnedContent}. */
    @Value("${chat.retrieval.pinned-similarity-floor:0.3}")
    private double pinnedSimilarityFloor;

    public HybridSearchService(ChunkSearchRepository searchRepo,
                                ReRankingService reRankingService,
                                EmbeddingModel embeddingModel) {
        this.searchRepo = searchRepo;
        this.reRankingService = reRankingService;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Runs hybrid retrieval for the given query and returns the final re-ranked result set.
     *
     * @param spaceKey restricts both the dense and lexical candidate pools to chunks from this
     *                 Confluence space. {@code null} (or blank) searches every ingested space.
     */
    public List<RetrievedChunk> search(String query, String spaceKey) {
        log.info("Hybrid search: {}{}", query,
                (spaceKey == null || spaceKey.isBlank()) ? "" : " (space=" + spaceKey + ")");

        float[] queryEmbedding = embed(query);
        String embeddingStr = toVectorString(queryEmbedding);

        List<RawCandidate> denseResults   = searchRepo.findTopNDense(embeddingStr, candidatePoolSize, spaceKey);
        List<RawCandidate> lexicalResults = searchRepo.findTopNLexical(query, candidatePoolSize, spaceKey);

        if (denseResults.isEmpty() && lexicalResults.isEmpty()) {
            log.info("No candidates from either dense or lexical retrieval");
            return List.of();
        }

        log.info("Hybrid search: {} dense + {} lexical candidates",
            denseResults.size(), lexicalResults.size());

        List<ReRankingService.ScoredCandidate> fused =
            fuseAndScore(denseResults, lexicalResults, queryEmbedding);

        List<RetrievedChunk> reranked = reRankingService.rerank(query, queryEmbedding, fused, topK);

        log.info("Hybrid search: {} dense + {} lexical → {} final chunks after ranking",
            denseResults.size(), lexicalResults.size(), reranked.size());
        return withPinnedContent(reranked, queryEmbedding);
    }

    /**
     * Guarantees a seat for any configured page's table chunks that are at least plausibly
     * on-topic for the query — gated by real cosine similarity against the query embedding, not
     * by hand-curated keyword phrases.
     *
     * <p>Exists because ranking-based relevance alone can leave an authoritative table chunk (an
     * inherently weaker embedding match than fluent prose on the same subject) permanently
     * uncompetitive against several closely-related sibling pages, regardless of how dense/lexical
     * fusion or MMR weights are tuned — a table can be genuinely relevant and still never win that
     * contest. This checks the pinned page's actual similarity to the query using the same
     * embedding already computed for retrieval, so it can't fire for a query that's merely
     * coincidentally similar in wording; it only overrides the *ranking* decision, not relevance
     * itself. Any number of pages can be configured; each is evaluated independently.
     */
    private List<RetrievedChunk> withPinnedContent(List<RetrievedChunk> results, float[] queryEmbedding) {
        List<String> configuredPageIds = parsePinnedPageIds();
        if (configuredPageIds.isEmpty()) return results;

        List<RetrievedChunk> combined = null;
        for (String pageId : configuredPageIds) {
            if (results.stream().anyMatch(c -> pageId.equals(c.getPageId()))) continue;
            if (combined != null && combined.stream().anyMatch(c -> pageId.equals(c.getPageId()))) continue;

            for (RawCandidate raw : searchRepo.findTableChunksByPage(pageId)) {
                RetrievedChunk chunk = searchRepo.toRetrievedChunk(raw, queryEmbedding);
                if (chunk.getSimilarity() < pinnedSimilarityFloor) continue;

                if (combined == null) combined = new ArrayList<>(results);
                combined.add(chunk);
                log.info("Pinned content: page {} chunk {} met the similarity floor ({} >= {})",
                        pageId, chunk.getChunkId(), chunk.getSimilarity(), pinnedSimilarityFloor);
            }
        }
        return combined != null ? combined : results;
    }

    private List<String> parsePinnedPageIds() {
        if (pinnedPageIds == null || pinnedPageIds.isBlank()) return List.of();
        return Arrays.stream(pinnedPageIds.split(","))
                .map(String::strip)
                .filter(id -> !id.isBlank())
                .toList();
    }

    private List<ReRankingService.ScoredCandidate> fuseAndScore(
            List<RawCandidate> denseResults, List<RawCandidate> lexicalResults,
            float[] queryEmbedding) {

        // De-duplicate: first occurrence from either list wins (dense has priority for ties)
        Map<String, RawCandidate> byId = new LinkedHashMap<>();
        denseResults.forEach(c  -> byId.putIfAbsent(c.chunkId(), c));
        lexicalResults.forEach(c -> byId.putIfAbsent(c.chunkId(), c));

        Map<String, Double> fusionScores = RankFusion.fuseWithScores(List.of(
            denseResults.stream().map(RawCandidate::chunkId).toList(),
            lexicalResults.stream().map(RawCandidate::chunkId).toList()
        ));

        return fusionScores.entrySet().stream()
            .map(entry -> {
                RawCandidate raw = byId.get(entry.getKey());
                if (raw == null) return null;
                RetrievedChunk chunk = searchRepo.toRetrievedChunk(raw, queryEmbedding);
                return new ReRankingService.ScoredCandidate(chunk, chunk.getEmbedding(), entry.getValue());
            })
            .filter(Objects::nonNull)
            .toList();
    }

    private float[] embed(String text) {
        try {
            return embeddingModel.embed(text);
        } catch (Exception e) {
            log.error("Failed to embed query", e);
            throw new RuntimeException("Failed to embed query", e);
        }
    }

    static String toVectorString(float[] values) {
        // pgvector does not accept Java's scientific notation (e.g. "1.0E-5").
        // Use fixed-point formatting so every component is a plain decimal.
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.8f", (double) values[i]));
        }
        return sb.append("]").toString();
    }
}
