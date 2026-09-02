package com.kssasarma.confluencebot.ingestion;

public interface IngestionService {
    IngestionResult ingestSpace(String spaceKey);
    IngestionResult ingestSpace(String spaceKey, boolean force);
    IngestionResult ingestPage(String pageId);
}
