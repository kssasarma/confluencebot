package com.kssasarma.confluencebot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of a space or page ingestion run")
public record IngestResponse(
        @Schema(description = "Outcome of the ingestion run", example = "SUCCESS",
                allowableValues = "SUCCESS")
        String status,

        @Schema(description = "Number of pages that were embedded (new or changed)", example = "47")
        int pagesProcessed,

        @Schema(description = "Total vector chunks stored across all processed pages", example = "312")
        int chunksStored,

        @Schema(description = "Pages whose Confluence version had not changed since the last run "
                + "and were therefore skipped", example = "3")
        int pagesSkipped,

        @Schema(description = "Wall-clock duration of the ingestion run in milliseconds", example = "18420")
        long durationMs
) {}
