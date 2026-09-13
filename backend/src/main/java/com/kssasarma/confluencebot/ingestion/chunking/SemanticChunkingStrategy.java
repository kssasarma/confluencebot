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
 * - TABLE sections: always split by row into batches of at most {@code table-chunk-size} tokens
 *   (deliberately smaller than the general chunk budget — see the field below), with the header
 *   row repeated on each split so every fragment stays a self-describing table.
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
     * Deliberately smaller than {@link #maxTokens}. A table's embedding is one vector for its
     * whole stored text (heading + caption + rows) — there's no separate "this part matters more"
     * signal to the embedding model. A caption of one or two sentences next to 40+ rows of bare
     * {@code Name | Provider | Status}-style cell text gets diluted into near-nothing, so the
     * resulting vector reads as "a table of categorical values" rather than anything resembling
     * the natural-language question it answers — regardless of how good the caption is. Capping
     * every table batch to a small budget keeps the caption-to-data ratio healthy in every chunk,
     * not only in tables that happen to be small enough to stay a single chunk under the general
     * budget.
     */
    @Value("${chat.retrieval.table-chunk-size:200}")
    private int tableRowBudgetTokens;

    /**
     * Chunks a single {@link ParsedSection} into one or more {@link ChunkedContent} records.
     *
     * @param section   the parsed section with heading, content, and type
     * @param pageTitle the page title, prepended to each chunk for retrieval context
     */
    public List<ChunkedContent> chunk(ParsedSection section, String pageTitle) {
        return chunk(section, pageTitle, "");
    }

    /**
     * Same as {@link #chunk(ParsedSection, String)}, with an extra line of narrative context
     * folded into every resulting chunk's prefix.
     *
     * <p>Built for {@code TABLE} sections: a table is stored as near-bare cell text (see
     * {@link #chunkTable}), which embeds and lexically matches far more weakly than prose on the
     * same subject — the caller (see {@code IngestionServiceImpl}) passes the immediately
     * preceding paragraph under the same heading, when one exists, so the table competes in
     * retrieval on closer to equal footing instead of relying on being rescued after the fact.
     * A blank caption is a no-op — the output is identical to the two-argument overload.
     *
     * @param caption one short line of prose context, or blank/{@code null} if none is available
     */
    public List<ChunkedContent> chunk(ParsedSection section, String pageTitle, String caption) {
        if (!section.hasContent()) return List.of();

        String headingPrefix = buildHeadingPrefix(pageTitle, section.heading(), caption);

        String chunkType = section.type().name();
        return switch (section.type()) {
            case CODE  -> chunkCode(section.content(), headingPrefix, chunkType);
            case TABLE -> chunkTable(section.content(), headingPrefix, chunkType);
            case TEXT  -> chunkText(section.content(), headingPrefix, chunkType);
            // Always resolved (replaced with the transcluded page's real sections) or dropped
            // by IngestionServiceImpl before chunking runs — never reaches here in practice.
            case EXCERPT_REFERENCE -> List.of();
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

    /**
     * Always splits by row (never keeps a large table as one chunk, even if it would technically
     * fit under {@link #maxTokens}) — see {@link #tableRowBudgetTokens}. The row budget reserves
     * space for {@code headingPrefix} up front, so the prefix can never push a split over
     * {@link #maxTokens} after the fact and force a silent tail truncation of the last rows.
     */
    private List<ChunkedContent> chunkTable(String table, String headingPrefix, String chunkType) {
        if (table.isBlank()) return List.of();
        int prefixTokens = estimateTokens(headingPrefix);
        // No floor above 1: a floor like 30 could exceed (maxTokens - prefixTokens) whenever the
        // prefix eats most of maxTokens, silently reintroducing the truncation this guards against.
        int rowBudget = Math.max(1, Math.min(tableRowBudgetTokens, maxTokens - prefixTokens));

        List<ChunkedContent> result = new ArrayList<>();
        for (String part : splitTableToBudget(table, rowBudget)) {
            result.add(new ChunkedContent(headingPrefix + part, chunkType));
        }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildHeadingPrefix(String pageTitle, String heading, String caption) {
        StringBuilder sb = new StringBuilder();
        if (pageTitle != null && !pageTitle.isBlank()) sb.append("Page: ").append(pageTitle).append("\n");
        if (heading   != null && !heading.isBlank())  sb.append('[').append(heading).append("]\n");
        if (caption   != null && !caption.isBlank())  sb.append(caption.strip()).append("\n");
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
     * Splits a table by row into batches of at most {@code rowBudget} tokens. Each split repeats
     * the first row (the header) so every fragment stays self-describing — orphaned data rows
     * with no column names are useless to both the retriever and the LLM. {@code rowBudget}
     * already has the caller's heading-prefix cost reserved out of it (see {@link #chunkTable}),
     * so no further trim is needed after the prefix is added back on — trimming a fully-assembled
     * split here as a defensive clamp only, in case token estimation is ever slightly off.
     */
    private List<String> splitTableToBudget(String table, int rowBudget) {
        if (table.isBlank()) return List.of();
        if (estimateTokens(table) <= rowBudget) return List.of(table);

        String[] rows = table.split("\n", -1);
        if (rows.length < 2) return List.of(trimToTokens(table, rowBudget));

        String header = rows[0];
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        boolean hasRows = false;

        for (int i = 1; i < rows.length; i++) {
            String row = rows[i].strip();
            if (row.isBlank()) continue;
            if (hasRows && estimateTokens(current.toString()) + estimateTokens(row) > rowBudget) {
                parts.add(trimToTokens(current.toString(), rowBudget));
                current = new StringBuilder(header);
                hasRows = false;
            }
            current.append("\n").append(row);
            hasRows = true;
        }
        if (hasRows) parts.add(trimToTokens(current.toString(), rowBudget));
        return parts.isEmpty() ? List.of(trimToTokens(table, rowBudget)) : parts;
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
