package com.kssasarma.confluencebot.api.dto;

import com.kssasarma.confluencebot.domain.IngestionScheduleEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Current state of a space's automatic re-ingestion schedule")
public record IngestionScheduleResponse(

        @Schema(description = "Schedule ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        UUID id,

        @Schema(description = "Confluence space key this schedule targets", example = "IT")
        String spaceKey,

        @Schema(description = "Whether the schedule is active", example = "true")
        boolean enabled,

        @Schema(description = "Re-ingestion interval in hours", example = "24")
        int intervalHours,

        @Schema(description = "Whether each run forces re-embedding of unchanged pages",
                example = "false")
        boolean force,

        @Schema(description = "When the schedule last fired, null if it has never run",
                nullable = true)
        OffsetDateTime lastRunAt,

        @Schema(description = "When the schedule will fire next")
        OffsetDateTime nextRunAt,

        @Schema(description = "When this schedule was created")
        OffsetDateTime createdAt,

        @Schema(description = "When this schedule was last modified")
        OffsetDateTime updatedAt,

        @Schema(description = "Email of the admin who created this schedule")
        String createdBy,

        @Schema(description = "Email of the admin who last modified this schedule")
        String updatedBy
) {
    public static IngestionScheduleResponse from(IngestionScheduleEntity e) {
        return new IngestionScheduleResponse(
                e.getId(),
                e.getSpaceKey(),
                e.isEnabled(),
                e.getIntervalHours(),
                e.isForce(),
                e.getLastRunAt(),
                e.getNextRunAt(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getCreatedBy(),
                e.getUpdatedBy());
    }
}
