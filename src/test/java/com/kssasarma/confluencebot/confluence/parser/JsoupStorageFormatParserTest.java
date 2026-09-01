package com.kssasarma.confluencebot.confluence.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsoupStorageFormatParserTest {

    private final JsoupStorageFormatParser parser = new JsoupStorageFormatParser();

    @Test
    void parse_null_returnsEmpty() {
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void parse_blank_returnsEmpty() {
        assertThat(parser.parse("   ")).isEmpty();
    }

    @Test
    void parse_simpleParagraph_returnsSingleUntitledSection() {
        List<ParsedSection> sections = parser.parse("<p>Hello world</p>");

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).hasHeading()).isFalse();
        assertThat(sections.get(0).content()).contains("Hello world");
    }

    @Test
    void parse_twoHeadings_createsTwoSections() {
        String xhtml = "<h2>Overview</h2><p>Intro text.</p><h2>Details</h2><p>Detail text.</p>";

        List<ParsedSection> sections = parser.parse(xhtml);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).heading()).isEqualTo("Overview");
        assertThat(sections.get(0).content()).contains("Intro text.");
        assertThat(sections.get(1).heading()).isEqualTo("Details");
        assertThat(sections.get(1).content()).contains("Detail text.");
    }

    @Test
    void parse_contentBeforeFirstHeading_capturedAsUntitledSection() {
        String xhtml = "<p>Preamble</p><h2>Section A</h2><p>Body</p>";

        List<ParsedSection> sections = parser.parse(xhtml);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).hasHeading()).isFalse();
        assertThat(sections.get(0).content()).contains("Preamble");
        assertThat(sections.get(1).heading()).isEqualTo("Section A");
    }

    @Test
    void parse_headingWithNoFollowingContent_isSkipped() {
        String xhtml = "<h2>Empty</h2><h2>Non-empty</h2><p>Content here</p>";

        List<ParsedSection> sections = parser.parse(xhtml);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).heading()).isEqualTo("Non-empty");
        assertThat(sections.get(0).content()).contains("Content here");
    }

    @Test
    void parse_h1ToH4_allRecognizedAsHeadingBoundaries() {
        String xhtml = "<h1>H1</h1><p>One</p><h3>H3</h3><p>Three</p><h4>H4</h4><p>Four</p>";

        List<ParsedSection> sections = parser.parse(xhtml);

        assertThat(sections).extracting(ParsedSection::heading)
                .containsExactly("H1", "H3", "H4");
    }

    @Test
    void parse_confluenceMacro_isStrippedFromOutput() {
        String xhtml = """
                <ac:structured-macro ac:name="info">
                  <ac:parameter ac:name="title">Note</ac:parameter>
                  <ac:rich-text-body><p>Macro body text</p></ac:rich-text-body>
                </ac:structured-macro>
                <p>Visible content</p>
                """;

        List<ParsedSection> sections = parser.parse(xhtml);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).content())
                .contains("Visible content")
                .doesNotContain("Macro body text");
    }

    @Test
    void parse_listItems_capturedWithoutDuplicationFromParent() {
        String xhtml = "<ul><li>Alpha</li><li>Beta</li></ul>";

        List<ParsedSection> sections = parser.parse(xhtml);

        assertThat(sections).hasSize(1);
        String content = sections.get(0).content();
        assertThat(content).contains("Alpha").contains("Beta");
        // Each item must appear exactly once — not duplicated via the parent ul element
        assertThat(content.indexOf("Alpha")).isEqualTo(content.lastIndexOf("Alpha"));
    }

    @Test
    void parse_tableCell_contentIsExtracted() {
        String xhtml = "<table><tr><td>Cell value</td></tr></table>";

        List<ParsedSection> sections = parser.parse(xhtml);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).content()).contains("Cell value");
    }

    @Test
    void parse_codeBlock_contentIsExtracted() {
        String xhtml = "<pre>System.out.println(\"hello\");</pre>";

        List<ParsedSection> sections = parser.parse(xhtml);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).content()).contains("System.out.println");
    }
}
