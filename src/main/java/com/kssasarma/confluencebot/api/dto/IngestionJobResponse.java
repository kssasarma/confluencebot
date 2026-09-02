package com.kssasarma.confluencebot.api.dto;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Status and result of a background ingestion job")
public record IngestionJobResponse(

        @Schema(description = "Unique job identifier", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        UUID jobId,

        @Schema(description = "Job type: SPACE or PAGE", example = "SPACE")
        String jobType,

        @Schema(description = "Confluence space key (SPACE jobs)", example = "IT")
        String spaceKey,

        @Schema(description = "Confluence page ID (PAGE jobs)", example = "131073")
        String pageId,

        @Schema(description = "Whether force re-ingestion was requested", example = "false")
        boolean force,

        @Schema(description = "Job status: PENDING, RUNNING, COMPLETED, or FAILED", example = "PENDING")
        String status,

        @Schema(description = "When the job was submitted")
        OffsetDateTime createdAt,

        @Schema(description = "When ingestion started (null while PENDING)")
        OffsetDateTime startedAt,

        @Schema(description = "When ingestion finished (null while PENDING or RUNNING)")
        OffsetDateTime completedAt,

        @Schema(description = "Pages embedded (populated on COMPLETED)", example = "47")
        Integer pagesProcessed,

        @Schema(description = "Vector chunks stored (populated on COMPLETED)", example = "312")
        Integer chunksStored,

        @Schema(description = "Pages skipped due to unchanged version (populated on COMPLETED)", example = "3")
        Integer pagesSkipped,

        @Schema(description = "Error detail (populated on FAILED)")
        String errorMessage
) {
    public static IngestionJobResponse from(IngestionJobEntity e) {
        return new IngestionJobResponse(
                e.getId(),
                e.getJobType().name(),
                e.getSpaceKey(),
                e.getPageId(),
                e.isForce(),
                e.getStatus().name(),
                e.getCreatedAt(),
                e.getStartedAt(),
                e.getCompletedAt(),
                e.getPagesProcessed(),
                e.getChunksStored(),
                e.getPagesSkipped(),
                e.getErrorMessage()
        );
    }
}
