package com.kssasarma.confluencebot.api.dto;

/**
 * A single cited source page returned with every chat response.
 *
 * url      — the Confluence page URL (always present when ingested correctly).
 * anchorUrl — url + "#" + section heading anchor; links directly to the relevant section.
 *             Falls back to url when no section heading is available.
 * score    — cosine similarity score of the best-matching chunk from this page (0–1).
 */
public record SourceReference(
        String pageId,
        String title,
        String url,
        String anchorUrl,
        String spaceKey,
        Double score
) {}
