package com.kssasarma.confluencebot.ingestion.chunking;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Heading-aware chunking strategy.
 *
 * - Sections (already split at headings by the parser) are used as primary chunk boundaries.
 * - Oversized sections are further split with a sentence-boundary-aware overlap.
 * - Each chunk is prefixed with "Page: {title}" to improve retrieval relevance.
 *
 * MAX_CHARS = 1500 ≈ 375 tokens at 4 chars/token — safe for 512-token embedding models.
 */
@Component
public class HeadingAwareChunkingStrategy implements ChunkingStrategy {

    private static final int MAX_CHARS_PER_CHUNK = 1500;
    private static final int OVERLAP_CHARS = 150;

    @Override
    public List<String> chunk(List<String> sections, String pageTitle) {
        List<String> chunks = new ArrayList<>();
        String titlePrefix = "Page: " + pageTitle + "\n\n";

        for (String section : sections) {
            if (section == null || section.isBlank()) continue;

            String prefixed = titlePrefix + section;

            if (prefixed.length() <= MAX_CHARS_PER_CHUNK) {
                chunks.add(prefixed.strip());
            } else {
                chunks.addAll(splitWithOverlap(prefixed));
            }
        }

        return chunks;
    }

    private List<String> splitWithOverlap(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + MAX_CHARS_PER_CHUNK, text.length());

            // Prefer breaking at a sentence boundary (period or newline)
            if (end < text.length()) {
                int lastPeriod = text.lastIndexOf('.', end);
                int lastNewline = text.lastIndexOf('\n', end);
                int breakPoint = Math.max(lastPeriod, lastNewline);

                if (breakPoint > start + (MAX_CHARS_PER_CHUNK / 2)) {
                    end = breakPoint + 1;
                }
            }

            String chunk = text.substring(start, end).strip();
            if (!chunk.isBlank()) {
                result.add(chunk);
            }

            // Overlap: step back by OVERLAP_CHARS for context continuity
            start = Math.max(start + 1, end - OVERLAP_CHARS);
        }

        return result;
    }
}
