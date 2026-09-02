package com.kssasarma.confluencebot.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tuning for the retrieval-confidence score reported alongside every answer.
 *
 * The weights are relative, not required to sum to one — the scorer normalises by their total, so
 * an operator can raise one without recomputing the rest.
 */
@Validated
@ConfigurationProperties(prefix = "chat.confidence")
public record ChatConfidenceProperties(

        /** Weight of the single best-matching chunk's similarity. */
        @PositiveOrZero double topSimilarityWeight,

        /** Weight of the mean similarity across the chunks sent to the model. */
        @PositiveOrZero double meanSimilarityWeight,

        /** Weight of how many distinct pages contributed evidence. */
        @PositiveOrZero double pageAgreementWeight,

        /** Weight of how much of the offered evidence the answer actually cited. */
        @PositiveOrZero double citationWeight,

        /** Similarity at or below which a chunk contributes nothing. Cosine similarity over a real
         *  corpus rarely falls below this even for unrelated text, so scores are rescaled against
         *  it rather than against zero. */
        @DecimalMin("0.0") @DecimalMax("0.99") double similarityFloor,

        /** Number of distinct agreeing pages treated as full corroboration. */
        @Positive int pageAgreementTarget,

        /** Number of cited excerpts treated as full citation coverage. A good short answer that
         *  needs one source should not be marked down for the four it did not need. */
        @Positive int sufficientCitations
) {

    public double totalWeight() {
        return topSimilarityWeight + meanSimilarityWeight + pageAgreementWeight + citationWeight;
    }
}
