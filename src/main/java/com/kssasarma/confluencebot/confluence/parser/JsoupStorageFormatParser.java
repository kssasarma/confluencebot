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

    @Override
    public List<ParsedSection> parse(String storageFormatXhtml) {
        if (storageFormatXhtml == null || storageFormatXhtml.isBlank()) {
            return List.of();
        }

        Document doc = Jsoup.parse(storageFormatXhtml);
        preserveLinkText(doc);
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

        // For divs and other containers, recurse and look for nested headings/tables/code
        if ("div".equals(tag) || "section".equals(tag) || "article".equals(tag)) {
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

    private void removeConfluenceMacros(Document doc) {
        doc.select("ac|structured-macro, ac|parameter, ac|plain-text-body, ac|rich-text-body").remove();
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
