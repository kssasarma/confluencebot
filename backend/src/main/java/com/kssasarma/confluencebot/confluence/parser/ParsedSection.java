package com.kssasarma.confluencebot.confluence.parser;

/**
 * A single logical section from a Confluence page, split at heading boundaries.
 * The heading is stored separately so it can be used to build section anchor URLs.
 * The type distinguishes prose (TEXT), code blocks (CODE), tables (TABLE), and an
 * unresolved cross-page transclusion reference (EXCERPT_REFERENCE) so the chunker
 * and ingestion pipeline can handle each category appropriately.
 */
public record ParsedSection(String heading, String content, SectionType type) {

    public enum SectionType { TEXT, CODE, TABLE, EXCERPT_REFERENCE }

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

    /**
     * An {@code ac:excerpt-include} macro: the page's storage format only carries the
     * transcluded page's title, never its actual content — {@code content()} holds that
     * title. Resolved (fetched and spliced in) at ingestion time; see
     * {@code IngestionServiceImpl#resolveExcerptReferences}. The chunker never sees this
     * type directly — it's always resolved or dropped before chunking runs.
     */
    public boolean isExcerptReference() { return type == SectionType.EXCERPT_REFERENCE; }
}
