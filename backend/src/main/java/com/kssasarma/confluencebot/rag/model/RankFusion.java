package com.kssasarma.confluencebot.rag.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reciprocal Rank Fusion — combines any number of independently-ranked ID lists
 * (e.g. dense vector-similarity ranking and a lexical full-text ranking) into one
 * fused ranking without requiring the two rankings' scores to be on a comparable scale.
 * An ID's fused score is the sum of 1/(k+rank) across every list it appears in (1-based
 * rank). An ID ranked highly by both signals rises to the top even if neither signal alone
 * considered it the single best match.
 */
public final class RankFusion {

    private static final int DEFAULT_K = 60;

    private RankFusion() {}

    public static List<String> fuse(List<List<String>> rankedIdLists) {
        return List.copyOf(fuseWithScores(rankedIdLists, DEFAULT_K).keySet());
    }

    /**
     * Same fusion but keeps each ID's numeric RRF score so re-ranking can use the strength
     * of agreement between signals, not just their relative order.
     */
    public static Map<String, Double> fuseWithScores(List<List<String>> rankedIdLists) {
        return fuseWithScores(rankedIdLists, DEFAULT_K);
    }

    public static Map<String, Double> fuseWithScores(List<List<String>> rankedIdLists, int k) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> ranked : rankedIdLists) {
            for (int i = 0; i < ranked.size(); i++) {
                scores.merge(ranked.get(i), 1.0 / (k + i + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .collect(Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue,
                (a, b) -> a, LinkedHashMap::new));
    }
}
