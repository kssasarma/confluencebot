package com.kssasarma.confluencebot.ingestion;

public interface IngestionService {
    IngestionResult ingestSpace(String spaceKey);
    IngestionResult ingestPage(String pageId);
}
