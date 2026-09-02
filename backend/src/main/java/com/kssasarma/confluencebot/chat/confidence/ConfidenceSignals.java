package com.kssasarma.confluencebot.chat.confidence;

import java.util.List;

/**
 * The evidence a {@link ConfidenceScorer} is allowed to reason about.
 *
 * Deliberately a plain value object rather than the retrieval results themselves: a scorer that
 * could see the chunks would be tempted to re-implement retrieval, and every new scoring idea
 * would then need the whole RAG pipeline to be testable. Everything here is a number.
 *
 * @param topSimilarity   cosine similarity of the single best-matching chunk (0–1)
 * @param meanSimilarity  mean similarity across the chunks actually sent to the model (0–1)
 * @param distinctPages   how many different Confluence pages contributed a chunk
 * @param retrievedChunks how many chunks were sent to the model
 * @param citedMarkers    how many distinct excerpt markers the finished answer actually cited
 * @param offeredMarkers  how many excerpt markers the answer could have cited
 */
public record ConfidenceSignals(
        double topSimilarity,
        double meanSimilarity,
        int distinctPages,
        int retrievedChunks,
        int citedMarkers,
        int offeredMarkers
) {

    public ConfidenceSignals {
        if (retrievedChunks < 0 || offeredMarkers < 0 || citedMarkers < 0 || distinctPages < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }

    /** Retrieval is known before generation; the citation counts are filled in afterwards. */
    public static ConfidenceSignals fromRetrieval(List<Double> similarities, int distinctPages) {
        if (similarities == null || similarities.isEmpty()) return empty();

        double top = similarities.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double mean = similarities.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return new ConfidenceSignals(top, mean, distinctPages, similarities.size(), 0, similarities.size());
    }

    public static ConfidenceSignals empty() {
        return new ConfidenceSignals(0.0, 0.0, 0, 0, 0, 0);
    }

    /** The same retrieval, now knowing how much of it the answer leaned on. */
    public ConfidenceSignals withCitedMarkers(int cited) {
        return new ConfidenceSignals(topSimilarity, meanSimilarity, distinctPages,
                retrievedChunks, Math.min(cited, offeredMarkers), offeredMarkers);
    }

    public boolean isEmpty() {
        return retrievedChunks == 0;
    }
}
