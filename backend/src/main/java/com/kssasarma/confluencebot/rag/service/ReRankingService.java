package com.kssasarma.confluencebot.rag.service;

import com.kssasarma.confluencebot.config.ChatRerankProperties;
import com.kssasarma.confluencebot.rag.model.CosineSimilarity;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Narrows a fused (dense + lexical) candidate pool to the final result set the LLM actually sees.
 *
 * Three stages:
 * 1. MMR (Maximal Marginal Relevance) — always applied. Greedily picks candidates that balance
 *    relevance to the query against dissimilarity from what's already been picked, so the final
 *    set is not dominated by near-duplicate chunks covering the same sentence.
 * 2. Table floor — always applied. A table chunk is cell text with little surrounding prose, so
 *    it routinely loses MMR's relevance term to a more fluent paragraph that only talks about the
 *    same subject — see {@link #ensureTableRepresented}. Losing the chunk that actually holds the
 *    tabular answer is the costlier mistake, so the fused pool's best-scoring table chunks — not
 *    just the first one MMR happens to keep — are guaranteed seats in the final set.
 * 3. LLM relevance re-rank — optional (config-gated). One extra LLM call that judges the
 *    resulting set directly against the question and reorders it. It only reorders what stages 1
 *    and 2 already selected — it cannot pull anything back from the discarded candidate pool — so
 *    a chunk dropped before this stage is gone regardless of how relevant the LLM would have
 *    judged it. Best-effort: any failure falls back to the prior order unchanged rather than
 *    blocking the answer.
 *
 * The model this pass calls is configured separately from the one that writes answers — see
 * {@link ChatRerankProperties}. It is handed in already built so this class never has to know
 * whether it is talking to the answer endpoint or somewhere else entirely.
 */
@Service
public class ReRankingService {

    private static final Logger log = LoggerFactory.getLogger(ReRankingService.class);

    private static final String TABLE_CHUNK_TYPE = "TABLE";

    private final RerankClient rerankClient;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final boolean llmRerankEnabled;

    @Value("${chat.retrieval.rerank-mmr-lambda:0.7}")
    private double mmrLambda;

    @Value("${chat.retrieval.rerank-fusion-weight:0.5}")
    private double fusionWeight;

    /** See {@link #ensureTableRepresented}. Defaults on: dropping tabular data silently is worse
     *  than dropping one more prose chunk MMR would otherwise have kept. */
    @Value("${chat.retrieval.guarantee-table-chunk:true}")
    private boolean guaranteeTableChunk;

    /** How many distinct table chunks the floor protects — see {@link #ensureTableRepresented}.
     *  More than one exists because a real corpus can hold several unrelated tables that are all
     *  plausible candidates for a similarly-worded question (e.g. a production model catalog and
     *  a separate sandbox environment's own model list) — guaranteeing only the single
     *  best-scoring table means a second, equally real one is still silently dropped whenever a
     *  weaker table happens to win MMR's natural selection first. */
    @Value("${chat.retrieval.guarantee-table-chunk-count:2}")
    private int guaranteeTableChunkCount;

    public ReRankingService(RerankClient rerankClient,
                             ChatRerankProperties rerankProperties,
                             @Qualifier("rerankCircuitBreaker") CircuitBreaker circuitBreaker,
                             @Qualifier("rerankBulkhead") Bulkhead bulkhead) {
        this.rerankClient = rerankClient;
        this.llmRerankEnabled = rerankProperties.enabled();
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
    }

    /** A fused candidate paired with its raw embedding and RRF fusion score — needed transiently
     * for MMR math and blended relevance scoring. */
    public record ScoredCandidate(RetrievedChunk chunk, float[] embedding, double fusionScore) {}

    public List<RetrievedChunk> rerank(String query, float[] queryEmbedding,
                                        List<ScoredCandidate> candidates, int finalTopK) {
        List<ScoredCandidate> mmrOrdered = mmr(queryEmbedding, candidates, finalTopK);
        mmrOrdered = ensureTableRepresented(candidates, mmrOrdered);
        List<RetrievedChunk> mmrChunks = mmrOrdered.stream().map(ScoredCandidate::chunk).toList();

        if (!llmRerankEnabled || mmrChunks.size() <= 1) {
            return mmrChunks;
        }
        return llmRerank(query, mmrChunks);
    }

    /**
     * Guarantees that the fused pool's best-scoring table chunks survive MMR's cut.
     *
     * <p>MMR scores relevance from cosine similarity and RRF agreement, both of which reward text
     * that reads like the query. A table section is stored as near-bare cell text (see
     * {@code SemanticChunkingStrategy#chunkTable}) — it carries the page title and heading but none
     * of the narrative phrasing a prose chunk about the same topic has, so it routinely loses that
     * relevance contest to a paragraph that only *talks about* the table's subject. The result is
     * an answer that cites the paragraph pointing at the data instead of the data.
     *
     * <p>Checking only "does the selection contain *a* table" is not enough on a real corpus: when
     * several unrelated tables are all plausible candidates for the same question, whichever one
     * MMR happens to keep — not necessarily the most relevant one — satisfies that check and blocks
     * every other table from ever being considered, even a much better match sitting right there in
     * the candidate pool. So this ranks every table candidate by fusion score and guarantees the top
     * {@link #guaranteeTableChunkCount} of them a seat, regardless of what MMR already kept.
     *
     * <p>This does not second-guess MMR's choice among prose chunks: a guaranteed table only ever
     * displaces a slot that isn't already holding one of the tables this pass is protecting.
     */
    private List<ScoredCandidate> ensureTableRepresented(List<ScoredCandidate> candidates,
                                                          List<ScoredCandidate> selected) {
        if (!guaranteeTableChunk || selected.isEmpty() || guaranteeTableChunkCount <= 0) return selected;

        List<ScoredCandidate> topTables = candidates.stream()
            .filter(c -> TABLE_CHUNK_TYPE.equals(c.chunk().getChunkType()))
            .sorted(Comparator.comparingDouble(ScoredCandidate::fusionScore).reversed())
            .limit(guaranteeTableChunkCount)
            .toList();
        if (topTables.isEmpty()) return selected;

        Set<String> guaranteedIds = new LinkedHashSet<>();
        for (ScoredCandidate table : topTables) guaranteedIds.add(table.chunk().getChunkId());

        List<ScoredCandidate> result = new ArrayList<>(selected);
        for (ScoredCandidate table : topTables) {
            boolean alreadyPresent = result.stream()
                .anyMatch(c -> c.chunk().getChunkId().equals(table.chunk().getChunkId()));
            if (alreadyPresent) continue;

            int replaceAt = lastReplaceableIndex(result, guaranteedIds);
            if (replaceAt < 0) break; // every slot already holds a table this pass is protecting

            log.info("Table floor: swapping in table chunk {} (page '{}') MMR would otherwise have dropped",
                    table.chunk().getChunkId(), table.chunk().getTitle());
            result.set(replaceAt, table);
        }
        return result;
    }

    /** The last slot not already holding one of the tables this pass is protecting — never
     *  sacrifice a table just guaranteed a seat to make room for another one. */
    private static int lastReplaceableIndex(List<ScoredCandidate> result, Set<String> guaranteedIds) {
        for (int i = result.size() - 1; i >= 0; i--) {
            if (!guaranteedIds.contains(result.get(i).chunk().getChunkId())) return i;
        }
        return -1;
    }

    // ── MMR ──────────────────────────────────────────────────────────────────

    private List<ScoredCandidate> mmr(float[] queryEmbedding, List<ScoredCandidate> candidates,
                                       int topK) {
        List<ScoredCandidate> remaining = new ArrayList<>(candidates);
        List<ScoredCandidate> selected  = new ArrayList<>();
        Map<ScoredCandidate, Double> relevance = blendedRelevance(queryEmbedding, candidates);

        while (!remaining.isEmpty() && selected.size() < topK) {
            ScoredCandidate best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (ScoredCandidate c : remaining) {
                double maxSimToSelected = selected.stream()
                    .mapToDouble(s -> CosineSimilarity.of(c.embedding(), s.embedding()))
                    .max().orElse(0.0);
                double score = mmrLambda * relevance.get(c) - (1 - mmrLambda) * maxSimToSelected;
                if (score > bestScore) { bestScore = score; best = c; }
            }
            selected.add(best);
            remaining.remove(best);
        }
        return selected;
    }

    /**
     * Blends each candidate's dense cosine similarity with its RRF fusion score so a candidate
     * that only lexical search ranked highly isn't silently dropped by an otherwise dense-only
     * relevance term. Both signals are min-max normalized (they live on different scales) before
     * blending.
     */
    private Map<ScoredCandidate, Double> blendedRelevance(float[] queryEmbedding,
                                                            List<ScoredCandidate> candidates) {
        Map<ScoredCandidate, Double> cosineMap  = new IdentityHashMap<>();
        Map<ScoredCandidate, Double> fusionMap  = new IdentityHashMap<>();
        for (ScoredCandidate c : candidates) {
            cosineMap.put(c, CosineSimilarity.of(queryEmbedding, c.embedding()));
            fusionMap.put(c, c.fusionScore());
        }
        Map<ScoredCandidate, Double> normCosine = minMaxNormalize(cosineMap);
        Map<ScoredCandidate, Double> normFusion = minMaxNormalize(fusionMap);

        Map<ScoredCandidate, Double> blended = new IdentityHashMap<>();
        for (ScoredCandidate c : candidates) {
            blended.put(c, fusionWeight * normFusion.get(c) + (1 - fusionWeight) * normCosine.get(c));
        }
        return blended;
    }

    private static Map<ScoredCandidate, Double> minMaxNormalize(Map<ScoredCandidate, Double> values) {
        if (values.isEmpty()) return values;
        double min = Collections.min(values.values());
        double max = Collections.max(values.values());
        double range = max - min;
        Map<ScoredCandidate, Double> out = new IdentityHashMap<>();
        for (Map.Entry<ScoredCandidate, Double> e : values.entrySet()) {
            out.put(e.getKey(), range > 0 ? (e.getValue() - min) / range : 1.0);
        }
        return out;
    }

    // ── LLM re-rank ──────────────────────────────────────────────────────────

    private List<RetrievedChunk> llmRerank(String query, List<RetrievedChunk> candidates) {
        try {
            List<Integer> zeroBasedOrder = bulkhead.executeSupplier(
                () -> circuitBreaker.executeSupplier(
                    () -> rerankClient.rerank(query, candidates.stream()
                            .map(RetrievedChunk::getContent).toList())
                )
            );
            return applyZeroBasedOrder(candidates, zeroBasedOrder);
        } catch (CallNotPermittedException e) {
            log.warn("LLM re-rank skipped — circuit breaker open: {}", e.getMessage());
            return candidates;
        } catch (BulkheadFullException e) {
            log.warn("LLM re-rank skipped — bulkhead full: {}", e.getMessage());
            return candidates;
        } catch (Exception e) {
            log.warn("LLM re-rank failed, keeping MMR order: {}", e.getMessage());
            return candidates;
        }
    }

    /** Reorders {@code candidates} per {@code order} (zero-based); anything the provider didn't mention
     * keeps its MMR position, appended after the ordered ones. */
    private static List<RetrievedChunk> applyZeroBasedOrder(List<RetrievedChunk> candidates,
                                                             List<Integer> order) {
        if (order.isEmpty()) return candidates;
        List<RetrievedChunk> reordered = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int idx : order) {
            if (idx >= 0 && idx < candidates.size() && used.add(idx)) {
                reordered.add(candidates.get(idx));
            }
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (!used.contains(i)) reordered.add(candidates.get(i));
        }
        return reordered;
    }

    private static String truncate(String text, int maxLen) {
        return (text != null && text.length() > maxLen) ? text.substring(0, maxLen) + "…" : text;
    }
}
