package com.kssasarma.confluencebot.chat.source;

import com.kssasarma.confluencebot.api.dto.SourceReference;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Turns retrieved chunks into the citations a reader sees.
 *
 * <p>Extracted from the chat service so that "what a source looks like" has one owner. The chat
 * pipeline's job is retrieve-prompt-generate-record; deciding how a page is presented is a
 * separate concern that changes for entirely different reasons.
 */
@Component
public class SourceReferenceFactory {

    /** Roughly two lines of prose — enough to judge a citation, short enough not to be the answer. */
    private static final int MIN_EXCERPT_LENGTH = 40;

    private final int excerptMaxLength;

    public SourceReferenceFactory(
            @Value("${chat.source.excerpt-max-length:240}") int excerptMaxLength) {
        this.excerptMaxLength = Math.max(excerptMaxLength, MIN_EXCERPT_LENGTH);
    }

    /**
     * Builds one reference per unique page, best match first.
     *
     * <p>Chunks arrive in re-ranked order, so the first chunk seen for a page is its best match;
     * later chunks from the same page are skipped rather than merged, because the excerpt should
     * show the passage that actually earned the citation.
     */
    public List<SourceReference> from(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return List.of();

        LinkedHashMap<String, SourceReference> byPageId = new LinkedHashMap<>();

        for (RetrievedChunk chunk : chunks) {
            if (chunk == null) continue;
            String pageId = chunk.getPageId();
            if (pageId == null || pageId.isBlank() || byPageId.containsKey(pageId)) continue;

            String pageUrl = orEmpty(chunk.getPageUrl());
            String heading = orEmpty(chunk.getSectionHeading());

            byPageId.put(pageId, new SourceReference(
                    pageId,
                    chunk.getTitle(),
                    pageUrl,
                    anchorUrl(pageUrl, heading),
                    chunk.getSpaceKey(),
                    chunk.getSimilarity(),
                    heading.isBlank() ? null : heading,
                    excerpt(chunk.getContent())));
        }

        return List.copyOf(new ArrayList<>(byPageId.values()));
    }

    /** Deep link to the matching section, falling back to the page when there is no heading. */
    private static String anchorUrl(String pageUrl, String heading) {
        if (pageUrl.isBlank() || heading.isBlank()) return pageUrl;
        return pageUrl + "#" + heading.replace(" ", "-");
    }

    /**
     * A readable extract of the matching passage.
     *
     * <p>Truncation lands on a word boundary when there is one nearby: cutting mid-word reads as a
     * rendering bug rather than as an excerpt. Whitespace is collapsed because chunk text carries
     * the line breaks of the original page, which do not survive a three-line clamp intact.
     */
    private String excerpt(String content) {
        if (content == null) return null;
        String flattened = content.replaceAll("\\s+", " ").strip();
        if (flattened.isEmpty()) return null;
        if (flattened.length() <= excerptMaxLength) return flattened;

        String clipped = flattened.substring(0, excerptMaxLength);
        int lastSpace = clipped.lastIndexOf(' ');
        if (lastSpace >= excerptMaxLength - 24) clipped = clipped.substring(0, lastSpace);

        return clipped.stripTrailing() + "…";
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
