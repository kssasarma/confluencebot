package com.kssasarma.confluencebot.ingestion.chunking;

import com.kssasarma.confluencebot.confluence.parser.ParsedSection;
import com.kssasarma.confluencebot.ingestion.chunking.SemanticChunkingStrategy.ChunkedContent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticChunkingStrategyTest {

    private final SemanticChunkingStrategy strategy = new SemanticChunkingStrategy();

    private SemanticChunkingStrategy withBudget(int chunkSize, int overlap, int tableChunkSize) {
        ReflectionTestUtils.setField(strategy, "maxTokens", chunkSize);
        ReflectionTestUtils.setField(strategy, "overlapTokens", overlap);
        ReflectionTestUtils.setField(strategy, "tableRowBudgetTokens", tableChunkSize);
        return strategy;
    }

    @Test
    void twoArgOverload_isUnaffectedByCaptionSupport() {
        withBudget(800, 100, 200);
        ParsedSection table = new ParsedSection("Models", "Name | Status\nA | Active",
                ParsedSection.SectionType.TABLE);

        List<ChunkedContent> withoutCaption = strategy.chunk(table, "Catalog");
        List<ChunkedContent> withBlankCaption = strategy.chunk(table, "Catalog", "");

        assertThat(withoutCaption).isEqualTo(withBlankCaption);
    }

    @Test
    void tableChunk_withCaption_includesCaptionInEveryChunk() {
        withBudget(800, 100, 200);
        ParsedSection table = new ParsedSection("Models", "Name | Status\nA | Active",
                ParsedSection.SectionType.TABLE);

        List<ChunkedContent> chunks = strategy.chunk(table, "Catalog",
                "The currently supported models are listed below.");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text())
                .contains("Page: Catalog")
                .contains("[Models]")
                .contains("The currently supported models are listed below.")
                .contains("Name | Status")
                .contains("A | Active");
    }

    @Test
    void tableChunk_captionSurvivesEverySplitOfALargeTable() {
        withBudget(800, 5, 40);
        StringBuilder rows = new StringBuilder("Name | Status\n");
        for (int i = 0; i < 30; i++) rows.append("Model-").append(i).append(" | Active\n");
        ParsedSection table = new ParsedSection("Models", rows.toString().strip(),
                ParsedSection.SectionType.TABLE);

        List<ChunkedContent> chunks = strategy.chunk(table, "Catalog", "Supported models:");

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text()).contains("Supported models:"));
    }

    @Test
    void everyRowSurvivesIntactAcrossAllSplitsOfALargeTable_noSilentTruncation() {
        // Regression test for a real bug: the row-splitting budget used to be blind to the
        // heading-prefix cost added back on afterward, so a caption could push a split over the
        // general token ceiling and silently truncate the split's trailing rows. The row budget
        // now reserves the prefix's cost up front, so every row must survive intact.
        withBudget(800, 5, 40);
        StringBuilder rows = new StringBuilder("Name | Status\n");
        for (int i = 0; i < 30; i++) rows.append("Model-").append(i).append(" | Active\n");
        ParsedSection table = new ParsedSection("Models", rows.toString().strip(),
                ParsedSection.SectionType.TABLE);
        String longCaption = "The complete, currently supported and actively maintained list of "
                + "models available for hosting in this environment is shown in the table below.";

        List<ChunkedContent> chunks = strategy.chunk(table, "Catalog", longCaption);

        for (int i = 0; i < 30; i++) {
            String needle = "Model-" + i + " | Active";
            boolean found = chunks.stream().anyMatch(c -> c.text().contains(needle));
            assertThat(found).as("row Model-%d should survive intact in some chunk", i).isTrue();
        }
        assertThat(chunks).allSatisfy(c -> assertThat(c.text()).doesNotContain("…"));
    }

    @Test
    void tableIsAlwaysSplitByItsOwnBudget_evenWhenItWouldFitUnderTheGeneralChunkSize() {
        // The core of the redesign: a table's embedding is one vector for its whole stored text,
        // so a caption sitting next to dozens of rows of bare cell text gets diluted into
        // near-nothing regardless of how large the *general* chunk-size budget is. Splitting must
        // be governed by the table-specific budget, not merely "does it fit under maxTokens".
        withBudget(800, 100, 100);
        StringBuilder rows = new StringBuilder("Name | Status\n");
        for (int i = 0; i < 40; i++) rows.append("Model-").append(i).append(" | Active\n");
        String rawTable = rows.toString().strip();
        ParsedSection table = new ParsedSection("Models", rawTable, ParsedSection.SectionType.TABLE);

        // Confirms the premise: this table fits comfortably under the general 800-token budget.
        assertThat(SemanticChunkingStrategy.estimateTokens(rawTable)).isLessThan(800);

        List<ChunkedContent> chunks = strategy.chunk(table, "Catalog", "Supported models:");

        assertThat(chunks.size()).isGreaterThan(1);
    }

    @Test
    void blankCaption_isANoOp() {
        withBudget(800, 100, 200);
        ParsedSection table = new ParsedSection("Models", "Name | Status\nA | Active",
                ParsedSection.SectionType.TABLE);

        List<ChunkedContent> chunks = strategy.chunk(table, "Catalog", "   ");

        assertThat(chunks.get(0).text()).doesNotContain("Supported models");
        assertThat(chunks.get(0).text()).isEqualTo(strategy.chunk(table, "Catalog").get(0).text());
    }
}
