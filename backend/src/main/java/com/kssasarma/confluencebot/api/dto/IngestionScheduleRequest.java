package com.kssasarma.confluencebot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(description = "Create or fully replace a space's automatic re-ingestion schedule")
public record IngestionScheduleRequest(

        @Schema(description = "How often to re-ingest the space (hours). Default 24 — once per day.",
                example = "24", minimum = "1", maximum = "8760")
        @Min(1) @Max(8760)
        int intervalHours,

        @Schema(description = "Whether the schedule is active. Set to false to pause without deleting.",
                example = "true")
        boolean enabled,

        @Schema(description = "Pass true to re-embed every page regardless of version, even if unchanged.",
                example = "false")
        boolean force
) {}
