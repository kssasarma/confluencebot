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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Narrows a fused (dense + lexical) candidate pool to the final result set the LLM actually sees.
 *
 * Two stages:
 * 1. MMR (Maximal Marginal Relevance) — always applied. Greedily picks candidates that balance
 *    relevance to the query against dissimilarity from what's already been picked, so the final
 *    set is not dominated by near-duplicate chunks covering the same sentence.
 * 2. LLM relevance re-rank — optional (config-gated). One extra LLM call that judges the
 *    MMR-selected set directly against the question and reorders it. Best-effort: any failure
 *    falls back to the MMR order unchanged rather than blocking the answer.
 *
 * The model this pass calls is configured separately from the one that writes answers — see
 * {@link ChatRerankProperties}. It is handed in already built so this class never has to know
 * whether it is talking to the answer endpoint or somewhere else entirely.
 */
@Service
public class ReRankingService {

    private static final Logger log = LoggerFactory.getLogger(ReRankingService.class);

    private final ChatClient chatClient;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final boolean llmRerankEnabled;

    @Value("${chat.retrieval.rerank-mmr-lambda:0.7}")
    private double mmrLambda;

    @Value("${chat.retrieval.rerank-fusion-weight:0.5}")
    private double fusionWeight;

    public ReRankingService(@Qualifier("rerankChatClient") ChatClient rerankChatClient,
                             ChatRerankProperties rerankProperties,
                             @Qualifier("rerankCircuitBreaker") CircuitBreaker circuitBreaker,
                             @Qualifier("rerankBulkhead") Bulkhead bulkhead) {
        this.chatClient = rerankChatClient;
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
        List<RetrievedChunk> mmrChunks = mmrOrdered.stream().map(ScoredCandidate::chunk).toList();

        if (!llmRerankEnabled || mmrChunks.size() <= 1) {
            return mmrChunks;
        }
        return llmRerank(query, mmrChunks);
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
        String prompt = buildRerankPrompt(query, candidates);
        try {
            String response = bulkhead.executeSupplier(
                () -> circuitBreaker.executeSupplier(
                    () -> chatClient.prompt().user(prompt).call().content()
                )
            );
            List<Integer> order = parseOrder(response, candidates.size());
            if (order.isEmpty()) {
                // Costs a call per question and changes nothing, so it is worth being able to see:
                // usually a model that explains itself, or one whose reply hit the token ceiling.
                log.debug("LLM re-rank reply was not an order, keeping MMR order: {}",
                        truncate(response, 200));
            }
            return applyOrder(candidates, order);
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

    private static String buildRerankPrompt(String query, List<RetrievedChunk> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Rank these documentation excerpts by how directly and completely they answer the question.\n");
        sb.append("Question: ").append(query).append("\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            sb.append('[').append(i + 1).append("] ")
              .append(truncate(candidates.get(i).getContent(), 500))
              .append("\n\n");
        }
        sb.append("Respond with ONLY the excerpt numbers in order from most to least relevant, comma-separated (e.g. \"3,1,2\"). No other text.");
        return sb.toString();
    }

    private static List<Integer> parseOrder(String response, int expectedCount) {
        if (response == null || response.isBlank()) return List.of();
        try {
            return Arrays.stream(response.trim().split("[,\\s]+"))
                .filter(s -> !s.isBlank())
                .map(Integer::parseInt)
                .limit(expectedCount)
                .toList();
        } catch (NumberFormatException e) {
            return List.of();
        }
    }

    /** Reorders {@code candidates} per {@code order} (1-based); anything the LLM didn't mention
     * keeps its MMR position, appended after the ordered ones. */
    private static List<RetrievedChunk> applyOrder(List<RetrievedChunk> candidates,
                                                    List<Integer> order) {
        if (order.isEmpty()) return candidates;
        List<RetrievedChunk> reordered = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int idx : order) {
            if (idx >= 1 && idx <= candidates.size() && used.add(idx)) {
                reordered.add(candidates.get(idx - 1));
            }
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (!used.contains(i + 1)) reordered.add(candidates.get(i));
        }
        return reordered;
    }

    private static String truncate(String text, int maxLen) {
        return (text != null && text.length() > maxLen) ? text.substring(0, maxLen) + "…" : text;
    }
}
