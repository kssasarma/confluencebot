package com.kssasarma.confluencebot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(description = "Partially update a space's automatic re-ingestion schedule. "
        + "Null fields are left unchanged.")
public record UpdateIngestionScheduleRequest(

        @Schema(description = "New re-ingestion interval in hours. Omit to leave unchanged.",
                example = "12", minimum = "1", maximum = "8760", nullable = true)
        @Min(1) @Max(8760)
        Integer intervalHours,

        @Schema(description = "Enable or disable the schedule. Omit to leave unchanged.",
                example = "false", nullable = true)
        Boolean enabled
) {}
