package com.kssasarma.confluencebot.confluence.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses Confluence Storage Format (XHTML) into typed {@link ParsedSection} objects.
 *
 * Strategy:
 * - Strip all Confluence macros (ac:structured-macro, ri:*, ac:link etc.)
 * - Walk the body's top-level children, treating tables and code blocks as separate sections
 *   (type CODE / TABLE) from regular prose (type TEXT).
 * - Within text sections, headings (h1–h4) flush the current section and start a new one.
 * - Each section tracks its heading separately to enable anchor URL construction.
 *
 * Keeping code and table content in their own typed sections allows the downstream
 * SemanticChunkingStrategy to chunk them with appropriate budgets and tag them with
 * the correct chunk_type in metadata (which the hybrid search and re-ranking use).
 */
@Component
public class JsoupStorageFormatParser implements StorageFormatParser {

    private static final Set<String> HEADING_TAGS = Set.of("h1", "h2", "h3", "h4");
    private static final Set<String> PROSE_TAGS   = Set.of("p", "li", "blockquote", "div");

    /** Marker attribute used to smuggle an excerpt-include's target title through the DOM
     *  walk below, past the generic macro cleanup that would otherwise destroy it. */
    private static final String EXCERPT_INCLUDE_TITLE_ATTR = "data-excerpt-include-title";

    @Override
    public List<ParsedSection> parse(String storageFormatXhtml) {
        if (storageFormatXhtml == null || storageFormatXhtml.isBlank()) {
            return List.of();
        }

        Document doc = Jsoup.parse(storageFormatXhtml);
        preserveLinkText(doc);
        preserveExcerptIncludeReferences(doc);
        removeConfluenceMacros(doc);

        List<ParsedSection> sections = new ArrayList<>();
        State state = new State();

        for (Node node : doc.body().childNodes()) {
            if (!(node instanceof Element el)) {
                if (node instanceof TextNode tn) {
                    String t = tn.text().strip();
                    if (!t.isBlank()) state.appendText(t);
                }
                continue;
            }
            processTopLevelElement(el, sections, state);
        }

        state.flush(state.currentHeading, sections);
        return sections;
    }

    private void processTopLevelElement(Element el, List<ParsedSection> sections, State state) {
        if (el.hasAttr(EXCERPT_INCLUDE_TITLE_ATTR)) {
            state.flush(state.currentHeading, sections);
            String title = el.attr(EXCERPT_INCLUDE_TITLE_ATTR);
            sections.add(new ParsedSection(state.currentHeading, title, ParsedSection.SectionType.EXCERPT_REFERENCE));
            return;
        }

        String tag = el.tagName().toLowerCase();

        if (HEADING_TAGS.contains(tag)) {
            state.flush(state.currentHeading, sections);
            state.currentHeading = el.text().strip();
            return;
        }

        if ("table".equals(tag)) {
            // Flush accumulated prose before the table, then emit a TABLE section
            state.flush(state.currentHeading, sections);
            String tableText = extractTableText(el);
            if (!tableText.isBlank()) {
                sections.add(new ParsedSection(state.currentHeading, tableText, ParsedSection.SectionType.TABLE));
            }
            return;
        }

        if ("pre".equals(tag) || isCodeBlock(el)) {
            // Flush accumulated prose before the code block, then emit a CODE section
            state.flush(state.currentHeading, sections);
            String codeText = el.wholeText().strip();
            if (codeText.isBlank()) codeText = el.text().strip();
            if (!codeText.isBlank()) {
                sections.add(new ParsedSection(state.currentHeading, codeText, ParsedSection.SectionType.CODE));
            }
            return;
        }

        // Lists: recurse into li children
        if ("ul".equals(tag) || "ol".equals(tag)) {
            for (Element child : el.children()) {
                collectLeafText(child, state);
            }
            return;
        }

        // For divs, other containers, and any leftover Confluence namespaced wrapper (e.g.
        // ac:layout / ac:layout-section / ac:layout-cell for page layouts, ac:task-list for
        // task lists) — recurse and look for nested headings/tables/code. Page layouts wrap
        // their columns' content, tables included, in these elements without going through
        // ac:structured-macro, so they survive removeConfluenceMacros untouched and must be
        // recursed into here or their content (tables especially) is silently dropped.
        if ("div".equals(tag) || "section".equals(tag) || "article".equals(tag) || tag.indexOf(':') >= 0) {
            for (Node child : el.childNodes()) {
                if (child instanceof Element childEl) {
                    processTopLevelElement(childEl, sections, state);
                } else if (child instanceof TextNode tn) {
                    String t = tn.text().strip();
                    if (!t.isBlank()) state.appendText(t);
                }
            }
            return;
        }

        // Prose: p, li, blockquote, etc.
        collectLeafText(el, state);
    }

    /** Extracts text from a table as a simple markdown-ish representation for readability. */
    private String extractTableText(Element table) {
        StringBuilder sb = new StringBuilder();
        for (Element row : table.select("tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : row.select("th, td")) {
                cells.add(cell.text().strip());
            }
            if (!cells.isEmpty()) {
                sb.append(String.join(" | ", cells)).append("\n");
            }
        }
        return sb.toString().strip();
    }

    /** Collects leaf-level text (avoids duplicating text from parent containers). */
    private void collectLeafText(Element el, State state) {
        String tag = el.tagName().toLowerCase();
        if (PROSE_TAGS.contains(tag) || "li".equals(tag)) {
            boolean hasContentChildren = el.children().stream()
                .anyMatch(c -> PROSE_TAGS.contains(c.tagName()) || "li".equals(c.tagName()));
            if (!hasContentChildren) {
                String text = el.text().strip();
                if (!text.isBlank()) state.appendText(text);
                return;
            }
            // Has nested content — recurse to avoid duplicate text
            for (Element child : el.children()) {
                collectLeafText(child, state);
            }
        }
    }

    private static boolean isCodeBlock(Element el) {
        String tag = el.tagName().toLowerCase();
        return "code".equals(tag) || el.hasClass("code") || el.hasAttr("data-language");
    }

    private void preserveLinkText(Document doc) {
        for (Element link : doc.select("ac|link")) {
            String text = extractLinkDisplayText(link);
            if (!text.isBlank()) {
                link.replaceWith(new TextNode(" " + text + " "));
            } else {
                link.remove();
            }
        }
    }

    private String extractLinkDisplayText(Element link) {
        Element plainBody = link.selectFirst("ac|plain-text-link-body");
        if (plainBody != null && !plainBody.text().isBlank()) return plainBody.text().strip();
        Element richBody = link.selectFirst("ac|rich-text-link-body");
        if (richBody != null && !richBody.text().isBlank()) return richBody.text().strip();
        Element riPage = link.selectFirst("ri|page");
        if (riPage != null) {
            String title = riPage.attr("ri:content-title");
            if (title.isBlank()) title = riPage.attr("content-title");
            return title.strip();
        }
        return "";
    }

    /**
     * An {@code ac:excerpt-include} macro's storage format never carries the transcluded
     * page's actual content — only a reference to it (an {@code ac:link}/{@code ri:page} naming
     * the target page's title, in the macro's one unnamed parameter). {@code preserveLinkText}
     * (run just before this) already turned that link into plain text; the generic cleanup in
     * {@link #removeConfluenceMacros} would otherwise delete the parameter — and that text with
     * it — before anything downstream ever saw it. This runs first and replaces the whole macro
     * with a plain marker element carrying the title, so the target page can be resolved and its
     * real content spliced in later (see {@code IngestionServiceImpl#resolveExcerptReferences}).
     */
    private void preserveExcerptIncludeReferences(Document doc) {
        for (Element macro : doc.select("ac|structured-macro[ac:name=excerpt-include]")) {
            String title = extractExcerptIncludeTitle(macro);
            if (!title.isBlank()) {
                macro.replaceWith(new Element("p").attr(EXCERPT_INCLUDE_TITLE_ATTR, title));
            } else {
                macro.remove();
            }
        }
    }

    /** The macro's one unnamed parameter holds the target page reference; named parameters
     *  (e.g. {@code nopanel}) are configuration, not the reference, and must be ignored. */
    private String extractExcerptIncludeTitle(Element macro) {
        for (Element param : macro.select("ac|parameter")) {
            if (param.attr("ac:name").isBlank()) {
                String text = param.text().strip();
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    private void removeConfluenceMacros(Document doc) {
        // Convert code macro bodies to <pre> so they survive as CODE sections.
        // ac:plain-text-body holds the raw code inside Confluence code blocks.
        for (Element plainBody : doc.select("ac|plain-text-body")) {
            String text = plainBody.wholeText().strip();
            if (text.isBlank()) text = plainBody.text().strip();
            if (!text.isBlank()) {
                plainBody.replaceWith(new Element("pre").text(text));
            } else {
                plainBody.remove();
            }
        }

        // Preserve content from info/note/warning/expand panels by unwrapping their bodies.
        // ac:rich-text-body wraps the HTML children — unwrapping promotes them to the parent.
        doc.select("ac|rich-text-body").unwrap();

        // Strip parameter noise, then unwrap structured macros so the preserved content survives.
        doc.select("ac|parameter").remove();
        doc.select("ac|structured-macro").unwrap();

        doc.select("ac|link, ac|image, ac|emoticon").remove();
        doc.select("ri|user, ri|page, ri|attachment").remove();
        doc.select("[ac:name]").remove();
        doc.select("script, style").remove();
    }

    private static class State {
        String currentHeading = "";
        StringBuilder buffer = new StringBuilder();

        void appendText(String text) {
            if (!buffer.isEmpty()) buffer.append("\n");
            buffer.append(text);
        }

        void flush(String heading, List<ParsedSection> sections) {
            String content = buffer.toString().strip();
            if (!content.isBlank()) {
                sections.add(new ParsedSection(heading, content, ParsedSection.SectionType.TEXT));
            }
            buffer = new StringBuilder();
        }
    }
}
