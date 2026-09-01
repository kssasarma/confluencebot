package com.kssasarma.confluencebot.confluence.parser;

/**
 * A single logical section from a Confluence page, split at heading boundaries.
 * The heading is stored separately so it can be used to build section anchor URLs.
 * The type distinguishes prose (TEXT), code blocks (CODE), and tables (TABLE)
 * so the chunker can handle each category appropriately.
 */
public record ParsedSection(String heading, String content, SectionType type) {

    public enum SectionType { TEXT, CODE, TABLE }

    /** Backward-compatible constructor — creates a TEXT section. */
    public ParsedSection(String heading, String content) {
        this(heading, content, SectionType.TEXT);
    }

    public boolean hasHeading() {
        return heading != null && !heading.isBlank();
    }

    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    public boolean isCode()  { return type == SectionType.CODE; }
    public boolean isTable() { return type == SectionType.TABLE; }
    public boolean isText()  { return type == SectionType.TEXT; }
}
