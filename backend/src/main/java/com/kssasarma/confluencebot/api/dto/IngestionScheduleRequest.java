package com.kssasarma.confluencebot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(description = "Create or fully replace a space's automatic re-ingestion schedule")
public record IngestionScheduleRequest(

        @Schema(description = "Schedule type: FIXED_INTERVAL (default) or CRON.",
                example = "FIXED_INTERVAL", allowableValues = {"FIXED_INTERVAL", "CRON"},
                nullable = true)
        String scheduleType,

        @Schema(description = "Re-ingestion interval in hours. Required when scheduleType is "
                + "FIXED_INTERVAL. Ignored for CRON schedules.",
                example = "24", minimum = "1", maximum = "8760", nullable = true)
        @Min(1) @Max(8760)
        Integer intervalHours,

        @Schema(description = "Whether the schedule is active. Set to false to pause without deleting.",
                example = "true")
        boolean enabled,

        @Schema(description = "Standard cron expression (5-field Linux syntax or 6-field Spring syntax). "
                + "Required when scheduleType is CRON. Example: '0 2 * * 1' fires every Monday at 02:00.",
                example = "0 2 * * 1", nullable = true)
        String cronExpression
) {}
