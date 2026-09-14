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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    @Value("${chat.retrieval.table-candidate-pool-size:10}")
    private int tableCandidatePoolSize;

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
        fused = injectTableCandidates(fused, queryEmbedding, spaceKey);
        fused = expandTableSiblings(fused, queryEmbedding);

        List<RetrievedChunk> reranked = reRankingService.rerank(query, queryEmbedding, fused, topK);

        log.info("Hybrid search: {} dense + {} lexical → {} final chunks after ranking",
            denseResults.size(), lexicalResults.size(), reranked.size());
        return reranked;
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

    /**
     * Runs a dedicated dense retrieval pass restricted to TABLE chunks and merges any that are
     * not already in the fused pool. Table cell text routinely scores below prose in joint
     * retrieval — even batches from a highly relevant table can miss the top-N cutoff entirely,
     * which means {@link #expandTableSiblings} never triggers for them (it can only expand a
     * group already represented in the pool). This pass guarantees at least one batch from each
     * of the closest TABLE chunks enters the pool, giving sibling expansion something to work
     * with. Pool size is intentionally small (default 10) — we are supplementing, not replacing,
     * the main retrieval pool.
     */
    private List<ReRankingService.ScoredCandidate> injectTableCandidates(
            List<ReRankingService.ScoredCandidate> fused,
            float[] queryEmbedding, String spaceKey) {

        List<RawCandidate> tableResults =
                searchRepo.findTopNDenseTable(toVectorString(queryEmbedding), tableCandidatePoolSize, spaceKey);
        if (tableResults.isEmpty()) return fused;

        Set<String> presentIds = new HashSet<>();
        double minFusionScore = Double.MAX_VALUE;
        for (ReRankingService.ScoredCandidate c : fused) {
            presentIds.add(c.chunk().getChunkId());
            if (c.fusionScore() < minFusionScore) minFusionScore = c.fusionScore();
        }

        List<ReRankingService.ScoredCandidate> extras = new ArrayList<>();
        for (RawCandidate raw : tableResults) {
            if (!presentIds.add(raw.chunkId())) continue;
            RetrievedChunk chunk = searchRepo.toRetrievedChunk(raw, queryEmbedding);
            extras.add(new ReRankingService.ScoredCandidate(chunk, chunk.getEmbedding(), minFusionScore));
            log.info("Table retrieval pass: injecting TABLE chunk {} (page '{}') absent from main pool",
                    raw.chunkId(), chunk.getPageId());
        }

        if (extras.isEmpty()) return fused;
        List<ReRankingService.ScoredCandidate> expanded = new ArrayList<>(fused);
        expanded.addAll(extras);
        return expanded;
    }

    /**
     * Ensures every row-batch chunk from a logical table is in the candidate pool when any of
     * its siblings already made it through retrieval. Retrieval ranks by query similarity — the
     * oldest/most generically-named rows in a large table routinely miss the pool even though
     * they belong to the same answer. This fetches those missing siblings directly from the DB
     * and appends them at the bottom of the pool; {@link ReRankingService#ensureTableRepresented}
     * then lifts the whole group through MMR's diversity cut.
     */
    private List<ReRankingService.ScoredCandidate> expandTableSiblings(
            List<ReRankingService.ScoredCandidate> fused, float[] queryEmbedding) {

        Set<String> presentIds = new HashSet<>();
        Map<String, String[]> tableGroups = new LinkedHashMap<>(); // groupKey → [pageId, heading]
        double minFusionScore = Double.MAX_VALUE;

        for (ReRankingService.ScoredCandidate c : fused) {
            presentIds.add(c.chunk().getChunkId());
            if ("TABLE".equals(c.chunk().getChunkType())) {
                String pageId  = c.chunk().getPageId();
                String heading = c.chunk().getSectionHeading() != null ? c.chunk().getSectionHeading() : "";
                if (pageId != null && !pageId.isBlank()) {
                    tableGroups.putIfAbsent(pageId + '\0' + heading, new String[]{pageId, heading});
                }
            }
            if (c.fusionScore() < minFusionScore) minFusionScore = c.fusionScore();
        }

        if (tableGroups.isEmpty()) return fused;

        double siblingScore = minFusionScore; // bottom of pool; group guarantee rescues them
        List<ReRankingService.ScoredCandidate> extras = new ArrayList<>();

        for (String[] parts : tableGroups.values()) {
            for (RawCandidate raw : searchRepo.findTableSiblings(parts[0], parts[1])) {
                if (!presentIds.add(raw.chunkId())) continue; // already in pool
                RetrievedChunk chunk = searchRepo.toRetrievedChunk(raw, queryEmbedding);
                extras.add(new ReRankingService.ScoredCandidate(chunk, chunk.getEmbedding(), siblingScore));
                log.info("Table sibling expansion: injecting chunk {} (page '{}', heading '{}') missed by retrieval",
                        raw.chunkId(), parts[0], parts[1]);
            }
        }

        if (extras.isEmpty()) return fused;
        List<ReRankingService.ScoredCandidate> expanded = new ArrayList<>(fused);
        expanded.addAll(extras);
        return expanded;
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
