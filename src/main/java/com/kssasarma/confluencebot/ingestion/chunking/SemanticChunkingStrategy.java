package com.kssasarma.confluencebot.ingestion.chunking;

import com.kssasarma.confluencebot.confluence.parser.ParsedSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Semantic chunking strategy for Confluence pages.
 *
 * Produces {@link ChunkedContent} objects (text + chunk_type) from typed {@link ParsedSection}s:
 *
 * - TEXT sections: split at blank-line paragraph boundaries; small paragraphs are merged up
 *   to the token budget; each chunk is prefixed with its section heading for retrieval context.
 *   Adjacent chunks share an overlap tail so a fact split across a boundary still has context
 *   in both resulting chunks.
 *
 * - CODE sections: kept as a single chunk when within budget; split by line otherwise. Each
 *   chunk is prefixed with its heading so the raw code is never shown without context.
 *
 * - TABLE sections: kept as a single chunk when within budget; split by row otherwise, with the
 *   header row repeated on each split so every fragment stays a self-describing table.
 *
 * Why typed sections matter for retrieval: a bare JSON response sample or endpoint table diluted
 * into a generic paragraph chunk is unreadable to both the retriever (its embedding gets averaged
 * with surrounding prose) and the LLM. Keeping each type as its own dedicated chunk lets the
 * hybrid search surface it directly and lets the prompt builder label it appropriately.
 */
@Component
public class SemanticChunkingStrategy {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunkingStrategy.class);

    private static final Pattern BLANK_LINE = Pattern.compile("\\n{2,}");

    @Value("${chat.retrieval.chunk-size:800}")
    private int maxTokens;

    @Value("${chat.retrieval.chunk-overlap:100}")
    private int overlapTokens;

    /**
     * Chunks a single {@link ParsedSection} into one or more {@link ChunkedContent} records.
     *
     * @param section   the parsed section with heading, content, and type
     * @param pageTitle the page title, prepended to each chunk for retrieval context
     */
    public List<ChunkedContent> chunk(ParsedSection section, String pageTitle) {
        if (!section.hasContent()) return List.of();

        String headingPrefix = buildHeadingPrefix(pageTitle, section.heading());

        String chunkType = section.type().name();
        return switch (section.type()) {
            case CODE  -> chunkCode(section.content(), headingPrefix, chunkType);
            case TABLE -> chunkTable(section.content(), headingPrefix, chunkType);
            case TEXT  -> chunkText(section.content(), headingPrefix, chunkType);
        };
    }

    // ── Text chunking ─────────────────────────────────────────────────────────

    private List<ChunkedContent> chunkText(String text, String headingPrefix, String chunkType) {
        List<String> paragraphs = splitParagraphs(text);
        List<String> merged     = mergeTinyParagraphs(paragraphs);

        List<ChunkedContent> result = new ArrayList<>();
        for (String para : merged) {
            String content = headingPrefix + para;
            content = trimToTokens(content, maxTokens);
            result.add(new ChunkedContent(content, chunkType));
        }
        return result;
    }

    // ── Code chunking ─────────────────────────────────────────────────────────

    private List<ChunkedContent> chunkCode(String code, String headingPrefix, String chunkType) {
        if (code.isBlank()) return List.of();
        List<ChunkedContent> result = new ArrayList<>();
        for (String part : splitLinesToBudget(code)) {
            String content = headingPrefix + "```\n" + part + "\n```";
            result.add(new ChunkedContent(trimToTokens(content, maxTokens), chunkType));
        }
        return result;
    }

    // ── Table chunking ────────────────────────────────────────────────────────

    private List<ChunkedContent> chunkTable(String table, String headingPrefix, String chunkType) {
        if (table.isBlank()) return List.of();
        List<ChunkedContent> result = new ArrayList<>();
        for (String part : splitTableToBudget(table)) {
            String content = headingPrefix + part;
            result.add(new ChunkedContent(trimToTokens(content, maxTokens), chunkType));
        }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildHeadingPrefix(String pageTitle, String heading) {
        StringBuilder sb = new StringBuilder();
        if (pageTitle != null && !pageTitle.isBlank()) sb.append("Page: ").append(pageTitle).append("\n");
        if (heading   != null && !heading.isBlank())  sb.append('[').append(heading).append("]\n");
        return sb.toString();
    }

    private List<String> splitParagraphs(String text) {
        String[] parts = BLANK_LINE.split(text.strip());
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.strip();
            if (!trimmed.isBlank()) result.add(trimmed);
        }
        return result;
    }

    /**
     * Merges paragraphs up to maxTokens per chunk; adjacent chunks share an overlap tail so a
     * fact split across a boundary still has context in both resulting chunks.
     */
    private List<String> mergeTinyParagraphs(List<String> paragraphs) {
        if (paragraphs.isEmpty()) return List.of();
        int minTokens = Math.max(30, maxTokens / 8);
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            if (current.isEmpty()) {
                current.append(para);
            } else if (estimateTokens(para) < minTokens) {
                current.append("\n\n").append(para);
            } else if (estimateTokens(current.toString()) + estimateTokens(para) <= maxTokens) {
                current.append("\n\n").append(para);
            } else {
                merged.add(current.toString());
                String carryOver = tailOverlap(current.toString());
                if (!carryOver.isEmpty() &&
                        estimateTokens(carryOver) + estimateTokens(para) > maxTokens) {
                    carryOver = "";
                }
                current = new StringBuilder(carryOver.isEmpty() ? para : carryOver + "\n\n" + para);
            }
        }
        if (!current.isEmpty()) merged.add(current.toString());
        return merged;
    }

    private String tailOverlap(String text) {
        if (overlapTokens <= 0) return "";
        int charLimit = overlapTokens * 4;
        if (text.length() <= charLimit) return text;
        String tail = text.substring(text.length() - charLimit);
        int firstSpace = tail.indexOf(' ');
        return (firstSpace > 0 && firstSpace < tail.length() - 1)
               ? tail.substring(firstSpace + 1) : tail;
    }

    private List<String> splitLinesToBudget(String code) {
        if (code.isBlank()) return List.of();
        if (estimateTokens(code) <= maxTokens) return List.of(code);
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            if (!current.isEmpty() &&
                    estimateTokens(current.toString()) + estimateTokens(line) > maxTokens) {
                parts.add(trimToTokens(current.toString(), maxTokens));
                current = new StringBuilder();
            }
            if (!current.isEmpty()) current.append("\n");
            current.append(line);
        }
        if (!current.isEmpty()) parts.add(trimToTokens(current.toString(), maxTokens));
        return parts;
    }

    /**
     * Splits a table by row when it exceeds the token budget.  Each split repeats the first row
     * (the header) so every fragment stays self-describing — orphaned data rows with no column
     * names are useless to both the retriever and the LLM.
     */
    private List<String> splitTableToBudget(String table) {
        if (table.isBlank()) return List.of();
        if (estimateTokens(table) <= maxTokens) return List.of(table);

        String[] rows = table.split("\n", -1);
        if (rows.length < 2) return List.of(trimToTokens(table, maxTokens));

        String header = rows[0];
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        boolean hasRows = false;

        for (int i = 1; i < rows.length; i++) {
            String row = rows[i].strip();
            if (row.isBlank()) continue;
            if (hasRows && estimateTokens(current.toString()) + estimateTokens(row) > maxTokens) {
                parts.add(trimToTokens(current.toString(), maxTokens));
                current = new StringBuilder(header);
                hasRows = false;
            }
            current.append("\n").append(row);
            hasRows = true;
        }
        if (hasRows) parts.add(trimToTokens(current.toString(), maxTokens));
        return parts.isEmpty() ? List.of(trimToTokens(table, maxTokens)) : parts;
    }

    private static String trimToTokens(String text, int limit) {
        if (estimateTokens(text) <= limit) return text;
        int charLimit = limit * 4;
        if (text.length() <= charLimit) return text;
        String trimmed = text.substring(0, charLimit);
        int lastSpace = trimmed.lastIndexOf(' ');
        return lastSpace > charLimit / 2 ? trimmed.substring(0, lastSpace) + "…" : trimmed + "…";
    }

    static int estimateTokens(String text) {
        return text == null ? 0 : (int) Math.ceil(text.length() / 4.0);
    }

    /** Carries the chunk_type string from the parsed section through to storage. */
    public record ChunkedContent(String text, String chunkType) {
        public String chunkType() {
            return chunkType != null ? chunkType : "TEXT";
        }
    }
}
