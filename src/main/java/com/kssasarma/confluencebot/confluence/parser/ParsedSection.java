package com.kssasarma.confluencebot.confluence.parser;

/**
 * A single logical section from a Confluence page, split at heading boundaries.
 * The heading is stored separately so it can be used to build section anchor URLs.
 */
public record ParsedSection(String heading, String content) {

    public boolean hasHeading() {
        return heading != null && !heading.isBlank();
    }

    public boolean hasContent() {
        return content != null && !content.isBlank();
    }
}
