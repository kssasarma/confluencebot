package com.kssasarma.confluencebot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "One page of ingestion job history, newest first")
public record IngestionJobPageResponse(

        @Schema(description = "Jobs in this page")
        List<IngestionJobResponse> jobs,

        @Schema(description = "Zero-based page number returned", example = "0")
        int page,

        @Schema(description = "Page size requested", example = "10")
        int size,

        @Schema(description = "Total number of ingestion jobs across all pages", example = "42")
        long totalElements,

        @Schema(description = "Total number of pages available", example = "5")
        int totalPages,

        @Schema(description = "Whether a page after this one exists", example = "true")
        boolean hasNext
) {
    public static IngestionJobPageResponse from(Page<IngestionJobResponse> jobPage) {
        return new IngestionJobPageResponse(
                jobPage.getContent(),
                jobPage.getNumber(),
                jobPage.getSize(),
                jobPage.getTotalElements(),
                jobPage.getTotalPages(),
                jobPage.hasNext()
        );
    }
}
