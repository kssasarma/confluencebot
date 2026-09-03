package com.kssasarma.confluencebot.chat.source;

import com.kssasarma.confluencebot.api.dto.SourceReference;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceReferenceFactoryTest {

    private final SourceReferenceFactory factory = new SourceReferenceFactory(240);

    private static RetrievedChunk.Builder chunk(String pageId) {
        return RetrievedChunk.builder()
                .chunkId("c-" + pageId)
                .pageId(pageId)
                .title("Password Reset Guide")
                .pageUrl("http://confluence/display/IT/Password+Reset")
                .spaceKey("IT")
                .similarity(0.87);
    }

    @Test
    void carriesTheSectionHeadingSeparatelyFromTheAnchor() {
        List<SourceReference> sources = factory.from(List.of(
                chunk("1").sectionHeading("Self Service Reset").content("Body").build()));

        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.sectionHeading()).isEqualTo("Self Service Reset");
            assertThat(source.anchorUrl()).endsWith("#Self-Service-Reset");
        });
    }

    @Test
    void fallsBackToThePageUrlWhenThereIsNoHeading() {
        List<SourceReference> sources = factory.from(List.of(chunk("1").content("Body").build()));

        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.sectionHeading()).isNull();
            assertThat(source.anchorUrl()).isEqualTo("http://confluence/display/IT/Password+Reset");
        });
    }

    @Test
    void keepsOnlyTheBestChunkPerPage() {
        List<SourceReference> sources = factory.from(List.of(
                chunk("1").content("Best match").similarity(0.91).build(),
                chunk("1").content("Weaker match from the same page").similarity(0.62).build(),
                chunk("2").content("Another page").build()));

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).excerpt()).isEqualTo("Best match");
        assertThat(sources.get(0).score()).isEqualTo(0.91);
    }

    @Test
    void collapsesTheLineBreaksOfTheOriginalPage() {
        List<SourceReference> sources = factory.from(List.of(
                chunk("1").content("Open the   page.\n\nThen click\tReset.").build()));

        assertThat(sources).singleElement()
                .extracting(SourceReference::excerpt)
                .isEqualTo("Open the page. Then click Reset.");
    }

    @Test
    void truncatesOnAWordBoundary() {
        String content = "word ".repeat(120);

        String excerpt = factory.from(List.of(chunk("1").content(content).build())).get(0).excerpt();

        assertThat(excerpt).endsWith("…").doesNotContain("wor…");
        assertThat(excerpt.length()).isLessThanOrEqualTo(241);
    }

    @Test
    void skipsChunksWithNoPage() {
        List<SourceReference> sources = factory.from(List.of(
                chunk(null).content("orphan").build(),
                chunk("  ").content("blank").build(),
                chunk("1").content("real").build()));

        assertThat(sources).singleElement().extracting(SourceReference::pageId).isEqualTo("1");
    }

    @Test
    void emptyRetrievalProducesNoSources() {
        assertThat(factory.from(List.of())).isEmpty();
        assertThat(factory.from(null)).isEmpty();
    }
}
