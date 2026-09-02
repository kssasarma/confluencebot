package com.kssasarma.confluencebot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Optional ingestion request body. Omit to use the configured default space.")
public record IngestRequest(
        @Schema(description = "Override the default space key to ingest a different space",
                example = "MYSPACE")
        String spaceKey,

        @Schema(description = "When true, bypasses the page-version cache and re-ingests every page "
                + "regardless of whether its Confluence version has changed. Use this after a "
                + "chunking strategy change to replace all stored chunks.",
                example = "true",
                defaultValue = "false")
        Boolean force
) {
    public boolean isForce() {
        return Boolean.TRUE.equals(force);
    }
}
