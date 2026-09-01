package com.kssasarma.confluencebot.confluence.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses Confluence Storage Format (XHTML) into clean text sections using Jsoup.
 *
 * Strategy:
 * - Strip all Confluence macros (ac:structured-macro, ri:*, ac:link etc.)
 * - Walk DOM; when a heading (h1–h4) is encountered, flush current section and start new one
 * - Collect text from meaningful content tags (p, li, td, th, blockquote, pre)
 * - Skip empty sections
 */
@Component
public class JsoupStorageFormatParser implements StorageFormatParser {

    private static final Set<String> HEADING_TAGS = Set.of("h1", "h2", "h3", "h4");
    private static final Set<String> CONTENT_TAGS = Set.of(
            "p", "li", "td", "th", "blockquote", "pre", "code", "div"
    );

    @Override
    public List<String> parse(String storageFormatXhtml) {
        if (storageFormatXhtml == null || storageFormatXhtml.isBlank()) {
            return List.of();
        }

        Document doc = Jsoup.parse(storageFormatXhtml);
        removeConfluenceMacros(doc);

        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (Element element : doc.body().getAllElements()) {
            String tag = element.tagName().toLowerCase();

            if (HEADING_TAGS.contains(tag)) {
                flushSection(current, sections);
                current = new StringBuilder();
                String headingText = element.text().strip();
                if (!headingText.isBlank()) {
                    current.append(headingText).append("\n");
                }
            } else if (CONTENT_TAGS.contains(tag)) {
                // Only process leaf-level elements to avoid duplicate text from parent containers
                if (element.children().stream().noneMatch(c -> CONTENT_TAGS.contains(c.tagName()))) {
                    String text = element.text().strip();
                    if (!text.isBlank()) {
                        current.append(text).append("\n");
                    }
                }
            }
        }

        flushSection(current, sections);

        return sections;
    }

    private void flushSection(StringBuilder buffer, List<String> sections) {
        String text = buffer.toString().strip();
        if (!text.isBlank()) {
            sections.add(text);
        }
    }

    private void removeConfluenceMacros(Document doc) {
        doc.select("ac|structured-macro, ac|parameter, ac|plain-text-body, ac|rich-text-body").remove();
        doc.select("ac|link, ac|image, ac|emoticon").remove();
        doc.select("ri|user, ri|page, ri|attachment").remove();
        doc.select("[ac:name]").remove();
        doc.select("script, style").remove();
    }
}
