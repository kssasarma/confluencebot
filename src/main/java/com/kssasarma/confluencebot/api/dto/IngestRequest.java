package com.kssasarma.confluencebot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Optional ingestion request body. Omit to use the configured default space.")
public record IngestRequest(
        @Schema(description = "Override the default space key to ingest a different space",
                example = "MYSPACE")
        String spaceKey
) {}
