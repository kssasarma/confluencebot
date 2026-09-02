package com.kssasarma.confluencebot.ingestion;

public record IngestionResult(
        int pagesProcessed,
        int chunksStored,
        int pagesSkipped,
        long durationMs
) {}
