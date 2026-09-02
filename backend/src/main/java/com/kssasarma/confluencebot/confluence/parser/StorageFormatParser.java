package com.kssasarma.confluencebot.confluence.parser;

import java.util.List;

/**
 * Parses Confluence Server Storage Format (XHTML-based) into clean text sections.
 * Each section represents a logical block of content, split at heading boundaries,
 * with the heading stored separately to support section anchor URL construction.
 */
public interface StorageFormatParser {
    List<ParsedSection> parse(String storageFormatXhtml);
}
