package com.kssasarma.confluencebot.confluence.parser;

import java.util.List;

/**
 * Parses Confluence Server Storage Format (XHTML-based) into clean text sections.
 * Each section represents a logical block of content, split at heading boundaries.
 */
public interface StorageFormatParser {
    List<String> parse(String storageFormatXhtml);
}
