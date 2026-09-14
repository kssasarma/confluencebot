package com.kssasarma.confluencebot.ingestion;

public interface IngestionService {
    IngestionResult ingestSpace(String spaceKey);
    IngestionResult ingestSpace(String spaceKey, boolean force);
    IngestionResult ingestPage(String pageId);

    /**
     * Permanently removes all ingested content for the given space — pages, vector chunks, and
     * generated suggestions. Irreversible; re-ingest the space to restore it.
     *
     * @return number of pages (and their chunks) removed
     */
    int deleteSpaceContent(String spaceKey);
}
