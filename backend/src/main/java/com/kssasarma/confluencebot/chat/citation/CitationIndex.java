package com.kssasarma.confluencebot.chat.citation;

import com.kssasarma.confluencebot.api.dto.Citation;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bridge between the numbered excerpts shown to the model and the pages they came from.
 *
 * <p>The prompt numbers excerpts {@code [1]}, {@code [2]}, … in chunk order and asks the model to
 * cite those numbers. This class owns both halves of that contract: it builds the marker → page
 * mapping the client needs to turn a marker into a hyperlink, and it reads a finished answer back
 * to find which markers were actually used.
 *
 * <p>Immutable and free of Spring — it is pure text and list work, so it is directly unit-testable.
 */
public final class CitationIndex {

    /**
     * A bracketed integer. Bounded to three digits so a long numeric literal in a code block —
     * {@code [10000]} in a config sample — cannot be mistaken for a citation.
     */
    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3})]");

    private static final CitationIndex EMPTY = new CitationIndex(List.of());

    /** Page id per marker; index 0 holds marker 1. A null entry means that chunk had no page. */
    private final List<String> pageIdByMarker;

    private CitationIndex(List<String> pageIdByMarker) {
        this.pageIdByMarker = pageIdByMarker;
    }

    /** Builds the index from the chunks in the exact order the prompt numbers them. */
    public static CitationIndex fromChunks(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return EMPTY;

        List<String> pageIds = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            String pageId = chunk == null ? null : chunk.getPageId();
            pageIds.add(pageId == null || pageId.isBlank() ? null : pageId);
        }
        // Not List.copyOf: it rejects nulls, and a null entry is exactly how a chunk with no
        // page id is represented — the marker exists but cannot be turned into a link.
        return new CitationIndex(Collections.unmodifiableList(pageIds));
    }

    public static CitationIndex empty() {
        return EMPTY;
    }

    /** How many markers the model was offered. */
    public int size() {
        return pageIdByMarker.size();
    }

    /**
     * The marker → page mapping to send to the client.
     *
     * <p>Markers whose chunk carried no page id are dropped: a marker that cannot become a link is
     * better rendered as plain text than as a dead one.
     */
    public List<Citation> citations() {
        List<Citation> citations = new ArrayList<>(pageIdByMarker.size());
        for (int i = 0; i < pageIdByMarker.size(); i++) {
            String pageId = pageIdByMarker.get(i);
            if (pageId != null) citations.add(new Citation(i + 1, pageId));
        }
        return List.copyOf(citations);
    }

    /**
     * Counts the distinct, resolvable markers the answer cites.
     *
     * <p>Repeating {@code [1]} five times is one piece of evidence, not five, so duplicates
     * collapse. Markers outside the offered range are ignored — they are the model inventing a
     * number, which is not evidence of anything.
     */
    public int countCitedIn(String answer) {
        return citedMarkers(answer).size();
    }

    /** The distinct resolvable markers present in the answer, in the order they first appear. */
    public Set<Integer> citedMarkers(String answer) {
        if (answer == null || answer.isEmpty() || pageIdByMarker.isEmpty()) return Set.of();

        Set<Integer> found = new LinkedHashSet<>();
        Matcher matcher = MARKER.matcher(answer);
        while (matcher.find()) {
            int marker = Integer.parseInt(matcher.group(1));
            if (marker >= 1 && marker <= pageIdByMarker.size() && pageIdByMarker.get(marker - 1) != null) {
                found.add(marker);
            }
        }
        return found;
    }

    /** The distinct pages the answer cites — several markers can point at one page. */
    public Set<String> citedPageIds(String answer) {
        Set<String> pages = new HashSet<>();
        for (int marker : citedMarkers(answer)) {
            pages.add(pageIdByMarker.get(marker - 1));
        }
        return pages;
    }
}
