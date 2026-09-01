package com.kssasarma.confluencebot.api.dto;

import com.kssasarma.confluencebot.domain.ConfluencePageEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "Summary of a Confluence page recorded in the ingestion registry")
public record PageSummaryResponse(

        @Schema(description = "Confluence numeric page ID", example = "131073")
        String pageId,

        @Schema(description = "Confluence space key", example = "IT")
        String spaceKey,

        @Schema(description = "Page title", example = "Password Reset Guide")
        String title,

        @Schema(description = "Full URL to the Confluence page",
                example = "http://confluence.example.com/display/IT/Password+Reset+Guide")
        String pageUrl,

        @Schema(description = "Confluence page version at the time of last ingestion", example = "5")
        int version,

        @Schema(description = "Number of vector chunks stored for this page", example = "7")
        int chunkCount,

        @Schema(description = "Timestamp when this page was last ingested",
                example = "2026-09-01T18:22:00Z")
        OffsetDateTime ingestedAt
) {
    public static PageSummaryResponse from(ConfluencePageEntity e) {
        return new PageSummaryResponse(
                e.getPageId(), e.getSpaceKey(), e.getTitle(), e.getPageUrl(),
                e.getVersion(), e.getChunkCount(), e.getIngestedAt());
    }
}
