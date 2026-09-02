package com.kssasarma.confluencebot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A Confluence page cited as a source for the answer")
public record SourceReference(
        @Schema(description = "Confluence numeric page ID", example = "131073")
        String pageId,

        @Schema(description = "Page title", example = "Password Reset Guide")
        String title,

        @Schema(description = "Full URL to the Confluence page",
                example = "http://confluence.example.com/display/IT/Password+Reset+Guide")
        String url,

        @Schema(description = "Deep-link URL to the specific section within the page (page URL + heading anchor). "
                + "Falls back to the page URL when no section heading is available.",
                example = "http://confluence.example.com/display/IT/Password+Reset+Guide#Self-Service-Reset")
        String anchorUrl,

        @Schema(description = "Confluence space key the page belongs to", example = "IT")
        String spaceKey,

        @Schema(description = "Cosine similarity score of the best-matching chunk from this page (0–1). "
                + "Higher means more relevant.", example = "0.87")
        Double score,

        @Schema(description = "Heading of the section the matching chunk came from. Carried separately "
                + "from anchorUrl so a client can show the breadcrumb without parsing a URL fragment.",
                example = "Self-Service Reset")
        String sectionHeading,

        @Schema(description = "Short extract of the matching chunk, so a reader can judge the citation "
                + "without opening Confluence. Truncated on a word boundary.",
                example = "Navigate to the login page and choose Forgot password. A reset link is sent…")
        String excerpt
) {

    /** The shape before section headings and excerpts were carried; kept for callers that have neither. */
    public SourceReference(String pageId, String title, String url, String anchorUrl,
                           String spaceKey, Double score) {
        this(pageId, title, url, anchorUrl, spaceKey, score, null, null);
    }
}
