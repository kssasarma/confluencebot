package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.ingestion.chunking.HeadingAwareChunkingStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeadingAwareChunkingStrategyTest {

    private final HeadingAwareChunkingStrategy strategy = new HeadingAwareChunkingStrategy();

    @Test
    void smallSection_returnsSingleChunkWithTitlePrefix() {
        List<String> result = strategy.chunk(List.of("Short section content."), "My Page");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).startsWith("Page: My Page");
        assertThat(result.get(0)).contains("Short section content.");
    }

    @Test
    void emptySection_isSkipped() {
        List<String> result = strategy.chunk(List.of("", "   ", "Real content."), "Test Page");
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("Real content.");
    }

    @Test
    void largeSection_isSplitIntoMultipleChunks() {
        String largeSection = "A".repeat(4000);
        List<String> result = strategy.chunk(List.of(largeSection), "Big Page");
        assertThat(result.size()).isGreaterThan(1);
        result.forEach(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(1800));
    }

    @Test
    void nullOrBlankSections_areSkipped() {
        List<String> result = strategy.chunk(List.of(), "Empty Page");
        assertThat(result).isEmpty();
    }
}
