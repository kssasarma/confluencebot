package com.kssasarma.confluencebot.chat.confidence;

import com.kssasarma.confluencebot.config.ChatConfidenceProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WeightedSignalConfidenceScorerTest {

    private static final ChatConfidenceProperties DEFAULTS =
            new ChatConfidenceProperties(0.35, 0.25, 0.15, 0.25, 0.30, 3, 2);

    private final ConfidenceScorer scorer = new WeightedSignalConfidenceScorer(DEFAULTS);

    @Test
    void noRetrieval_scoresZero() {
        assertThat(scorer.score(ConfidenceSignals.empty())).isZero();
        assertThat(scorer.score(null)).isZero();
    }

    @Test
    void strongRetrievalThatTheAnswerCites_scoresHigh() {
        ConfidenceSignals signals = ConfidenceSignals
                .fromRetrieval(List.of(0.92, 0.88, 0.85, 0.81), 3)
                .withCitedMarkers(3);

        assertThat(scorer.score(signals)).isGreaterThan(0.75);
    }

    @Test
    void weakRetrievalScoresLow() {
        ConfidenceSignals signals = ConfidenceSignals
                .fromRetrieval(List.of(0.34, 0.31, 0.30), 1)
                .withCitedMarkers(1);

        assertThat(scorer.score(signals)).isLessThan(0.35);
    }

    /**
     * The signal that catches a model answering from memory: retrieval was excellent, and the
     * answer used none of it.
     */
    @Test
    void excellentRetrievalThatTheAnswerIgnores_scoresLowerThanOneThatCitesIt() {
        ConfidenceSignals retrieval = ConfidenceSignals.fromRetrieval(List.of(0.94, 0.9, 0.88), 3);

        double cited = scorer.score(retrieval.withCitedMarkers(2));
        double uncited = scorer.score(retrieval.withCitedMarkers(0));

        assertThat(uncited).isLessThan(cited);
        assertThat(cited - uncited).isGreaterThan(0.2);
    }

    @Test
    void oneStrongPageScoresLowerThanSeveralAgreeingPages() {
        List<Double> similarities = List.of(0.9, 0.88, 0.86);

        double onePage = scorer.score(ConfidenceSignals.fromRetrieval(similarities, 1).withCitedMarkers(2));
        double threePages = scorer.score(ConfidenceSignals.fromRetrieval(similarities, 3).withCitedMarkers(2));

        assertThat(threePages).isGreaterThan(onePage);
    }

    /**
     * A short answer that needed one source is not a worse answer than one that cited four, so
     * coverage saturates rather than scaling with the size of the context.
     */
    @Test
    void citingEnoughIsTreatedAsFullCoverage() {
        ConfidenceSignals retrieval = ConfidenceSignals.fromRetrieval(List.of(0.9, 0.85, 0.8, 0.75), 3);

        assertThat(scorer.score(retrieval.withCitedMarkers(2)))
                .isEqualTo(scorer.score(retrieval.withCitedMarkers(4)));
    }

    /**
     * At the floor the two similarity signals contribute nothing at all; what remains is the
     * small amount of credit for a single page having matched, which is the intended shape — the
     * score is near-zero rather than exactly zero.
     */
    @Test
    void similaritiesBelowTheFloorContributeNothing() {
        ConfidenceSignals atFloor = ConfidenceSignals
                .fromRetrieval(List.of(0.30, 0.28, 0.25), 1)
                .withCitedMarkers(0);

        double onePage = scorer.score(atFloor);
        double noPages = scorer.score(new ConfidenceSignals(0.30, 0.28, 0, 3, 0, 3));

        assertThat(noPages).isZero();
        assertThat(onePage).isLessThan(0.1);
    }

    @Test
    void scoreAlwaysStaysWithinRange() {
        ConfidenceSignals perfect = ConfidenceSignals
                .fromRetrieval(List.of(1.0, 1.0, 1.0, 1.0, 1.0), 9)
                .withCitedMarkers(5);

        assertThat(scorer.score(perfect)).isBetween(0.0, 1.0).isEqualTo(1.0);
    }

    @Test
    void citedMarkersNeverExceedWhatWasOffered() {
        ConfidenceSignals signals = ConfidenceSignals
                .fromRetrieval(List.of(0.8, 0.7), 2)
                .withCitedMarkers(99);

        assertThat(signals.citedMarkers()).isEqualTo(2);
    }
}
