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
    void parse_confluenceMacro_richTextBodyContentIsPreserved() {
        // Info/note/warning panels use ac:rich-text-body — their content must survive
        // so pages that rely heavily on macros are not silently emptied.
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
                .contains("Macro body text");
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
    void parse_tableInsideLayoutSection_isCapturedAsTableSection() {
        // Confluence page layouts wrap column content in ac:layout / ac:layout-section /
        // ac:layout-cell directly (not via ac:structured-macro), so a table placed in a
        // layout column must still be recursed into and captured as a TABLE section.
        String xhtml = """
                <ac:layout>
                  <ac:layout-section ac:type="single">
                    <ac:layout-cell>
                      <table><tr><th>Name</th><th>Value</th></tr><tr><td>Foo</td><td>Bar</td></tr></table>
                    </ac:layout-cell>
                  </ac:layout-section>
                </ac:layout>
                """;

        List<ParsedSection> sections = parser.parse(xhtml);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).isTable()).isTrue();
        assertThat(sections.get(0).content()).contains("Foo").contains("Bar");
    }

    @Test
    void parse_codeBlock_contentIsExtracted() {
        String xhtml = "<pre>System.out.println(\"hello\");</pre>";

        List<ParsedSection> sections = parser.parse(xhtml);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).content()).contains("System.out.println");
    }

    @Test
    void parse_excerptInclude_producesAnExcerptReferenceSectionNamingTheTargetPage() {
        // excerpt-include's storage format never carries the transcluded content, only a
        // reference to the page that has it -- the one unnamed parameter holds an ac:link
        // wrapping a ri:page naming that page's title.
        String xhtml = """
                <p>Intro text</p>
                <ac:structured-macro ac:name="excerpt-include" ac:schema-version="1">
                  <ac:parameter ac:name="">
                    <ac:link><ri:page ri:content-title="Target Page Title" /></ac:link>
                  </ac:parameter>
                  <ac:parameter ac:name="nopanel">true</ac:parameter>
                </ac:structured-macro>
                """;

        List<ParsedSection> sections = parser.parse(xhtml);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).content()).contains("Intro text");
        assertThat(sections.get(1).isExcerptReference()).isTrue();
        // Exactly the title -- the "nopanel" named parameter must not leak into it.
        assertThat(sections.get(1).content()).isEqualTo("Target Page Title");
    }

    @Test
    void parse_excerptIncludeUnderAHeading_carriesThatHeading() {
        String xhtml = """
                <h2>Models</h2>
                <ac:structured-macro ac:name="excerpt-include">
                  <ac:parameter ac:name=""><ac:link><ri:page ri:content-title="Model Table Source" /></ac:link></ac:parameter>
                </ac:structured-macro>
                """;

        List<ParsedSection> sections = parser.parse(xhtml);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).isExcerptReference()).isTrue();
        assertThat(sections.get(0).heading()).isEqualTo("Models");
        assertThat(sections.get(0).content()).isEqualTo("Model Table Source");
    }

    @Test
    void parse_excerptIncludeWithNoResolvableTargetReference_producesNoSection() {
        String xhtml = """
                <ac:structured-macro ac:name="excerpt-include">
                  <ac:parameter ac:name="nopanel">true</ac:parameter>
                </ac:structured-macro>
                """;

        List<ParsedSection> sections = parser.parse(xhtml);

        assertThat(sections).isEmpty();
    }
}
