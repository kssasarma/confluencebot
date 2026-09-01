package com.kssasarma.confluencebot.ingestion.chunking;

import java.util.List;

/**
 * Strategy Pattern — defines the contract for chunking parsed text sections.
 *
 * Implementations can be swapped (e.g., fixed-size, semantic, heading-aware)
 * without touching the IngestionService pipeline.
 */
public interface ChunkingStrategy {
    /**
     * @param sections  parsed text sections from StorageFormatParser
     * @param pageTitle original Confluence page title, prepended to each chunk
     * @return list of final text chunks, ready for embedding
     */
    List<String> chunk(List<String> sections, String pageTitle);
}
