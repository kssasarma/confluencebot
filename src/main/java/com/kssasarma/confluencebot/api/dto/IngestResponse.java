package com.kssasarma.confluencebot.api.dto;

public record IngestResponse(
        String status,
        int pagesProcessed,
        int chunksStored,
        int pagesSkipped,
        long durationMs
) {}
