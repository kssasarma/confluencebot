package com.kssasarma.confluencebot.ingestion.chunking;

import com.kssasarma.confluencebot.confluence.parser.ParsedSection;
import com.kssasarma.confluencebot.ingestion.chunking.SemanticChunkingStrategy.ChunkedContent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticChunkingStrategyTest {

    private final SemanticChunkingStrategy strategy = new SemanticChunkingStrategy();

    private SemanticChunkingStrategy withBudget(int chunkSize, int overlap) {
        ReflectionTestUtils.setField(strategy, "maxTokens", chunkSize);
        ReflectionTestUtils.setField(strategy, "overlapTokens", overlap);
        return strategy;
    }

    @Test
    void twoArgOverload_isUnaffectedByCaptionSupport() {
        withBudget(800, 100);
        ParsedSection table = new ParsedSection("Models", "Name | Status\nA | Active",
                ParsedSection.SectionType.TABLE);

        List<ChunkedContent> withoutCaption = strategy.chunk(table, "Catalog");
        List<ChunkedContent> withBlankCaption = strategy.chunk(table, "Catalog", "");

        assertThat(withoutCaption).isEqualTo(withBlankCaption);
    }

    @Test
    void tableChunk_withCaption_includesCaptionInEveryChunk() {
        withBudget(800, 100);
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
        withBudget(40, 5);
        StringBuilder rows = new StringBuilder("Name | Status\n");
        for (int i = 0; i < 30; i++) rows.append("Model-").append(i).append(" | Active\n");
        ParsedSection table = new ParsedSection("Models", rows.toString().strip(),
                ParsedSection.SectionType.TABLE);

        List<ChunkedContent> chunks = strategy.chunk(table, "Catalog", "Supported models:");

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text()).contains("Supported models:"));
    }

    @Test
    void blankCaption_isANoOp() {
        withBudget(800, 100);
        ParsedSection table = new ParsedSection("Models", "Name | Status\nA | Active",
                ParsedSection.SectionType.TABLE);

        List<ChunkedContent> chunks = strategy.chunk(table, "Catalog", "   ");

        assertThat(chunks.get(0).text()).doesNotContain("Supported models");
        assertThat(chunks.get(0).text()).isEqualTo(strategy.chunk(table, "Catalog").get(0).text());
    }
}
