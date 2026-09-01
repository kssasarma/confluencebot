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
        Double score
) {}
