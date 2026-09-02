package com.kssasarma.confluencebot.chat.citation;

import com.kssasarma.confluencebot.api.dto.Citation;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationIndexTest {

    private static RetrievedChunk chunk(String pageId) {
        return RetrievedChunk.builder().chunkId("c-" + pageId).pageId(pageId).content("x").build();
    }

    @Test
    void mapsEachMarkerToThePageOfTheChunkInThatPosition() {
        CitationIndex index = CitationIndex.fromChunks(List.of(chunk("10"), chunk("20"), chunk("30")));

        assertThat(index.citations()).containsExactly(
                new Citation(1, "10"), new Citation(2, "20"), new Citation(3, "30"));
    }

    /**
     * The reason the mapping has to be explicit: two chunks of one page produce two markers that
     * point at a single source, so pairing marker n with source n would mis-link.
     */
    @Test
    void twoChunksOfOnePageProduceTwoMarkersForThatPage() {
        CitationIndex index = CitationIndex.fromChunks(List.of(chunk("10"), chunk("10"), chunk("20")));

        assertThat(index.citations()).containsExactly(
                new Citation(1, "10"), new Citation(2, "10"), new Citation(3, "20"));
    }

    @Test
    void dropsMarkersWhoseChunkHasNoPage() {
        CitationIndex index = CitationIndex.fromChunks(List.of(chunk("10"), chunk(null), chunk("30")));

        assertThat(index.citations()).containsExactly(new Citation(1, "10"), new Citation(3, "30"));
        assertThat(index.size()).isEqualTo(3);
    }

    @Test
    void countsTheDistinctMarkersAnAnswerCites() {
        CitationIndex index = CitationIndex.fromChunks(List.of(chunk("10"), chunk("20"), chunk("30")));

        assertThat(index.countCitedIn("Do this [1], then that [3].")).isEqualTo(2);
    }

    @Test
    void repeatingAMarkerIsOnePieceOfEvidence() {
        CitationIndex index = CitationIndex.fromChunks(List.of(chunk("10"), chunk("20")));

        assertThat(index.countCitedIn("[1] and again [1] and once more [1]")).isEqualTo(1);
    }

    @Test
    void ignoresMarkersOutsideTheOfferedRange() {
        CitationIndex index = CitationIndex.fromChunks(List.of(chunk("10"), chunk("20")));

        assertThat(index.countCitedIn("See [7] and [0] and [99].")).isZero();
    }

    @Test
    void ignoresLongNumericLiteralsThatAreNotCitations() {
        CitationIndex index = CitationIndex.fromChunks(List.of(chunk("10")));

        assertThat(index.countCitedIn("Set the timeout to [10000] milliseconds.")).isZero();
    }

    @Test
    void resolvesMarkersBackToDistinctPages() {
        CitationIndex index = CitationIndex.fromChunks(List.of(chunk("10"), chunk("10"), chunk("20")));

        assertThat(index.citedPageIds("Both [1] and [2] and also [3]")).containsExactlyInAnyOrder("10", "20");
    }

    @Test
    void emptyRetrievalHasNothingToResolve() {
        assertThat(CitationIndex.empty().citations()).isEmpty();
        assertThat(CitationIndex.fromChunks(List.of()).countCitedIn("[1]")).isZero();
        assertThat(CitationIndex.fromChunks(null).size()).isZero();
    }

    @Test
    void handlesNullAndEmptyAnswers() {
        CitationIndex index = CitationIndex.fromChunks(List.of(chunk("10")));

        assertThat(index.countCitedIn(null)).isZero();
        assertThat(index.countCitedIn("")).isZero();
    }
}
