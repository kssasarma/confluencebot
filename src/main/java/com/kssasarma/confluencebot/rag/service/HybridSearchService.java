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

    public HybridSearchService(ChunkSearchRepository searchRepo,
                                ReRankingService reRankingService,
                                EmbeddingModel embeddingModel) {
        this.searchRepo = searchRepo;
        this.reRankingService = reRankingService;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Runs hybrid retrieval for the given query and returns the final re-ranked result set.
     */
    public List<RetrievedChunk> search(String query) {
        log.info("Hybrid search: {}", query);

        float[] queryEmbedding = embed(query);
        String embeddingStr = toVectorString(queryEmbedding);

        List<RawCandidate> denseResults   = searchRepo.findTopNDense(embeddingStr, candidatePoolSize);
        List<RawCandidate> lexicalResults = searchRepo.findTopNLexical(query, candidatePoolSize);

        if (denseResults.isEmpty() && lexicalResults.isEmpty()) {
            log.info("No candidates from either dense or lexical retrieval");
            return List.of();
        }

        log.info("Hybrid search: {} dense + {} lexical candidates",
            denseResults.size(), lexicalResults.size());

        List<ReRankingService.ScoredCandidate> fused =
            fuseAndScore(denseResults, lexicalResults, queryEmbedding);

        List<RetrievedChunk> reranked = reRankingService.rerank(query, queryEmbedding, fused, topK);

        log.info("Hybrid search: {} dense + {} lexical → {} final chunks after re-ranking",
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

    private float[] embed(String text) {
        try {
            return embeddingModel.embed(text);
        } catch (Exception e) {
            log.error("Failed to embed query", e);
            throw new RuntimeException("Failed to embed query", e);
        }
    }

    static String toVectorString(float[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(values[i]);
        }
        return sb.append("]").toString();
    }
}
