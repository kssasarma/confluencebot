package com.kssasarma.confluencebot.chat.confidence;

import com.kssasarma.confluencebot.config.ChatConfidenceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * The default scorer: a weighted blend of four independent signals.
 *
 * <p>Each signal answers a different question, which is why no single one is trusted alone:
 * <ul>
 *   <li><b>Top similarity</b> — did anything in the corpus match well? A single strong hit is the
 *       most direct evidence, and the most easily fooled by a near-duplicate heading.</li>
 *   <li><b>Mean similarity</b> — was the <em>whole</em> context relevant, or one good chunk padded
 *       with noise? This is what separates a focused retrieval from a lucky one.</li>
 *   <li><b>Page agreement</b> — did several independent pages cover the topic? Corroboration
 *       across documents is worth more than the same page matching twice.</li>
 *   <li><b>Citation coverage</b> — did the model actually use what it was given? An answer that
 *       cites nothing was written from the model's own memory, whatever retrieval scored.</li>
 * </ul>
 *
 * <p>Similarities are rescaled against a configured floor before they are weighed. Cosine
 * similarity over a real corpus rarely drops below ~0.3 even for unrelated text, so treating a
 * raw 0.35 as "35% confident" would systematically overstate a miss.
 *
 * <p>Registered with {@link ConditionalOnMissingBean} so a deployment can drop in its own
 * {@link ConfidenceScorer} without excluding this class.
 */
@Component
@ConditionalOnMissingBean(ConfidenceScorer.class)
public class WeightedSignalConfidenceScorer implements ConfidenceScorer {

    private final ChatConfidenceProperties properties;

    public WeightedSignalConfidenceScorer(ChatConfidenceProperties properties) {
        this.properties = properties;
    }

    @Override
    public double score(ConfidenceSignals signals) {
        if (signals == null || signals.isEmpty()) return 0.0;

        double weighted =
                  properties.topSimilarityWeight()  * rescale(signals.topSimilarity())
                + properties.meanSimilarityWeight() * rescale(signals.meanSimilarity())
                + properties.pageAgreementWeight()  * pageAgreement(signals)
                + properties.citationWeight()       * citationCoverage(signals);

        double totalWeight = properties.totalWeight();
        return totalWeight <= 0 ? 0.0 : clamp(weighted / totalWeight);
    }

    /**
     * Maps a raw cosine similarity onto 0–1 relative to the configured floor, so the part of the
     * range that never occurs in practice does not inflate every score.
     */
    private double rescale(double similarity) {
        double floor = properties.similarityFloor();
        if (similarity <= floor) return 0.0;
        return clamp((similarity - floor) / (1.0 - floor));
    }

    /**
     * Saturating credit for corroboration: the second page that agrees is worth far more than the
     * fifth, so this rises steeply and then flattens rather than scaling linearly.
     */
    private double pageAgreement(ConfidenceSignals signals) {
        int target = Math.max(properties.pageAgreementTarget(), 1);
        return clamp((double) signals.distinctPages() / target);
    }

    /**
     * How much of the offered evidence the answer leaned on.
     *
     * <p>An answer that cites nothing scores zero here even when retrieval was excellent — that is
     * the intended behaviour, and it is the signal that catches a model answering from memory.
     * Citing a couple of excerpts is treated as full coverage: a good short answer that needs one
     * source should not be penalised for the four it did not need.
     */
    private double citationCoverage(ConfidenceSignals signals) {
        if (signals.offeredMarkers() == 0) return 0.0;
        int sufficient = Math.min(properties.sufficientCitations(), signals.offeredMarkers());
        if (sufficient <= 0) return 0.0;
        return clamp((double) signals.citedMarkers() / sufficient);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
