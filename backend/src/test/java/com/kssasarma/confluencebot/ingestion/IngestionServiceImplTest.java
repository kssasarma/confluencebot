package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.confluence.ConfluenceClient;
import com.kssasarma.confluencebot.confluence.dto.ConfluencePageDetail;
import com.kssasarma.confluencebot.confluence.dto.SpaceMetadata;
import com.kssasarma.confluencebot.confluence.parser.ParsedSection;
import com.kssasarma.confluencebot.confluence.parser.StorageFormatParser;
import com.kssasarma.confluencebot.config.ConfluenceProperties;
import com.kssasarma.confluencebot.domain.ConfluencePageEntity;
import com.kssasarma.confluencebot.ingestion.chunking.SemanticChunkingStrategy;
import com.kssasarma.confluencebot.ingestion.chunking.SemanticChunkingStrategy.ChunkedContent;
import com.kssasarma.confluencebot.repository.ConfluencePageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionServiceImplTest {

    @Mock private ConfluenceClient confluenceClient;
    @Mock private StorageFormatParser parser;
    @Mock private SemanticChunkingStrategy chunkingStrategy;
    @Mock private VectorStore vectorStore;
    @Mock private ConfluencePageRepository pageRepository;
    @Mock private JdbcTemplate jdbcTemplate;

    // Direct instantiation avoids mocking a record
    private final ConfluenceProperties props = new ConfluenceProperties(
            "http://confluence.example.com", "test-pat", "ENG", 100, 30);

    private IngestionServiceImpl service;

    private static final SpaceMetadata SPACE_WITH_DESC = new SpaceMetadata(
            "ENG", "Engineering",
            new SpaceMetadata.SpaceDescription(
                    new SpaceMetadata.SpaceDescription.Plain("Engineering space description")),
            new SpaceMetadata.Homepage("hp1", "Engineering Home"));

    private static final SpaceMetadata SPACE_NO_DESC = new SpaceMetadata(
            "ENG", "Engineering", null, null);

    @BeforeEach
    void setUp() {
        service = new IngestionServiceImpl(
                confluenceClient, parser, chunkingStrategy,
                vectorStore, pageRepository, props, jdbcTemplate);
    }

    @Test
    void ingestSpace_pageVersionUnchanged_pageIsSkipped() {
        ConfluencePageDetail page = page("p1", "Guide", 3);
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_WITH_DESC);
        when(confluenceClient.fetchAllPages("ENG")).thenReturn(List.of(page));
        when(pageRepository.findVersionByPageId("p1")).thenReturn(3);

        IngestionResult result = service.ingestSpace("ENG");

        assertThat(result.pagesSkipped()).isEqualTo(1);
        assertThat(result.pagesProcessed()).isEqualTo(0);
    }

    @Test
    void ingestSpace_pageVersionChanged_pageIsProcessedAndChunksStored() {
        ConfluencePageDetail page = page("p2", "Guide", 5);
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_WITH_DESC);
        when(confluenceClient.fetchAllPages("ENG")).thenReturn(List.of(page));
        when(pageRepository.findVersionByPageId("p2")).thenReturn(4);
        when(parser.parse(anyString()))
                .thenReturn(List.of(new ParsedSection("Intro", "Some text")));
        when(chunkingStrategy.chunk(any(), eq("Guide")))
                .thenReturn(List.of(new ChunkedContent("chunk one", "TEXT")));
        when(pageRepository.findById("p2")).thenReturn(Optional.empty());
        when(pageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IngestionResult result = service.ingestSpace("ENG");

        assertThat(result.pagesProcessed()).isEqualTo(1);
        assertThat(result.chunksStored()).isEqualTo(1);
    }

    @Test
    void ingestSpace_pageProcessed_tracksTheSpaceNameFromMetadata() {
        ConfluencePageDetail page = page("p2", "Guide", 5);
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_WITH_DESC);
        when(confluenceClient.fetchAllPages("ENG")).thenReturn(List.of(page));
        when(pageRepository.findVersionByPageId("p2")).thenReturn(null);
        when(parser.parse(anyString()))
                .thenReturn(List.of(new ParsedSection("Intro", "Some text")));
        when(chunkingStrategy.chunk(any(), eq("Guide")))
                .thenReturn(List.of(new ChunkedContent("chunk one", "TEXT")));
        when(pageRepository.findById("p2")).thenReturn(Optional.empty());
        when(pageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingestSpace("ENG");

        ArgumentCaptor<ConfluencePageEntity> saved = ArgumentCaptor.forClass(ConfluencePageEntity.class);
        verify(pageRepository).save(saved.capture());
        assertThat(saved.getValue().getSpaceKey()).isEqualTo("ENG");
        assertThat(saved.getValue().getSpaceName()).isEqualTo("Engineering");
    }

    @Test
    void ingestSpace_onePageFails_remainingPagesStillProcessed() {
        ConfluencePageDetail badPage = page("bad", "Bad Page", 1);
        ConfluencePageDetail goodPage = page("good", "Good Page", 1);
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_WITH_DESC);
        when(confluenceClient.fetchAllPages("ENG")).thenReturn(List.of(badPage, goodPage));
        when(pageRepository.findVersionByPageId(anyString())).thenReturn(null);
        when(parser.parse(anyString()))
                .thenThrow(new RuntimeException("simulated parse failure"))
                .thenReturn(List.of(new ParsedSection("", "Good content")));
        when(chunkingStrategy.chunk(any(), eq("Good Page")))
                .thenReturn(List.of(new ChunkedContent("chunk", "TEXT")));
        when(pageRepository.findById("good")).thenReturn(Optional.empty());
        when(pageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IngestionResult result = service.ingestSpace("ENG");

        assertThat(result.pagesProcessed()).isEqualTo(1);
    }

    @Test
    void ingestSpace_blankSpaceDescription_spaceOverviewNotAddedToVectorStore() {
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_NO_DESC);
        when(confluenceClient.fetchAllPages("ENG")).thenReturn(List.of());

        service.ingestSpace("ENG");

        verify(vectorStore, never()).add(any());
    }

    @Test
    void ingestSpace_nonBlankDescription_spaceOverviewDocumentIngested() {
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_WITH_DESC);
        when(confluenceClient.fetchAllPages("ENG")).thenReturn(List.of());

        service.ingestSpace("ENG");

        verify(vectorStore).add(argThat(docs -> docs.stream()
                .anyMatch(d -> "space_overview".equals(d.getMetadata().get("document_type")))));
    }

    @Test
    void ingestSpace_trackedPageNoLongerInConfluence_isDeletedFromChunksAndTracking() {
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_WITH_DESC);
        when(confluenceClient.fetchAllPages("ENG")).thenReturn(List.of());
        when(jdbcTemplate.query(eq("DELETE FROM confluence_pages WHERE space_key = ? AND page_id <> ALL (?) RETURNING page_id"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of("stale1"));

        service.ingestSpace("ENG");

        verify(jdbcTemplate).update(
                eq("DELETE FROM confluence_chunks WHERE metadata->>'page_id' = ANY (?)"),
                any(PreparedStatementSetter.class));
        verify(pageRepository, never()).deleteById(anyString());
    }

    @Test
    void ingestSpace_forcedRun_stillRemovesPageNoLongerInConfluence() {
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_WITH_DESC);
        when(confluenceClient.fetchAllPages("ENG")).thenReturn(List.of());
        when(jdbcTemplate.query(eq("DELETE FROM confluence_pages WHERE space_key = ? AND page_id <> ALL (?) RETURNING page_id"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of("stale2"));

        service.ingestSpace("ENG", true);

        verify(jdbcTemplate).update(
                eq("DELETE FROM confluence_chunks WHERE metadata->>'page_id' = ANY (?)"),
                any(PreparedStatementSetter.class));
    }

    @Test
    void ingestSpace_noPagesRemoved_chunkCleanupNotCalled() {
        ConfluencePageDetail page = page("p3", "Guide", 1);
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_WITH_DESC);
        when(confluenceClient.fetchAllPages("ENG")).thenReturn(List.of(page));
        when(jdbcTemplate.query(eq("DELETE FROM confluence_pages WHERE space_key = ? AND page_id <> ALL (?) RETURNING page_id"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(pageRepository.findVersionByPageId("p3")).thenReturn(1);

        service.ingestSpace("ENG");

        verify(jdbcTemplate, never()).update(
                eq("DELETE FROM confluence_chunks WHERE metadata->>'page_id' = ANY (?)"),
                any(PreparedStatementSetter.class));
    }

    @Test
    void ingestPage_tableFollowingSameHeadingParagraph_capturesCaptionFromPrecedingText() {
        ConfluencePageDetail page = page("sp1", "Spec Page", 2);
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_WITH_DESC);
        when(confluenceClient.fetchPage("sp1")).thenReturn(page);
        when(parser.parse(anyString())).thenReturn(List.of(
                new ParsedSection("Models", "The currently supported models are listed below.",
                        ParsedSection.SectionType.TEXT),
                new ParsedSection("Models", "Name | Status\nA | Active",
                        ParsedSection.SectionType.TABLE)));
        when(chunkingStrategy.chunk(any(), eq("Spec Page")))
                .thenReturn(List.of(new ChunkedContent("intro chunk", "TEXT")));
        when(chunkingStrategy.chunk(any(), eq("Spec Page"), anyString()))
                .thenReturn(List.of(new ChunkedContent("table chunk", "TABLE")));
        when(pageRepository.findById("sp1")).thenReturn(Optional.empty());
        when(pageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingestPage("sp1");

        ArgumentCaptor<String> caption = ArgumentCaptor.forClass(String.class);
        verify(chunkingStrategy).chunk(any(), eq("Spec Page"), caption.capture());
        assertThat(caption.getValue()).isEqualTo("The currently supported models are listed below.");
    }

    @Test
    void ingestPage_tableFollowingParagraphUnderADifferentHeading_fallsBackToASyntheticCaption() {
        ConfluencePageDetail page = page("sp1", "Spec Page", 2);
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_WITH_DESC);
        when(confluenceClient.fetchPage("sp1")).thenReturn(page);
        when(parser.parse(anyString())).thenReturn(List.of(
                new ParsedSection("Intro", "Unrelated paragraph from an earlier section.",
                        ParsedSection.SectionType.TEXT),
                new ParsedSection("Models", "Name | Status\nA | Active",
                        ParsedSection.SectionType.TABLE)));
        when(chunkingStrategy.chunk(any(), eq("Spec Page")))
                .thenReturn(List.of(new ChunkedContent("intro chunk", "TEXT")));
        when(chunkingStrategy.chunk(any(), eq("Spec Page"), anyString()))
                .thenReturn(List.of(new ChunkedContent("table chunk", "TABLE")));
        when(pageRepository.findById("sp1")).thenReturn(Optional.empty());
        when(pageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingestPage("sp1");

        // The preceding paragraph doesn't share the table's heading, so it's not used as the
        // caption -- but the table still gets a synthetic one derived from its own header row,
        // rather than no natural-language framing at all.
        verify(chunkingStrategy).chunk(any(), eq("Spec Page"), eq("The Models table lists Name, Status."));
    }

    @Test
    void ingestPage_tableIsFirstSectionOnThePage_fallsBackToASyntheticCaption() {
        ConfluencePageDetail page = page("sp1", "Spec Page", 2);
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_WITH_DESC);
        when(confluenceClient.fetchPage("sp1")).thenReturn(page);
        when(parser.parse(anyString())).thenReturn(List.of(
                new ParsedSection("Models", "Name | Status\nA | Active", ParsedSection.SectionType.TABLE)));
        when(chunkingStrategy.chunk(any(), eq("Spec Page"), anyString()))
                .thenReturn(List.of(new ChunkedContent("table chunk", "TABLE")));
        when(pageRepository.findById("sp1")).thenReturn(Optional.empty());
        when(pageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingestPage("sp1");

        verify(chunkingStrategy).chunk(any(), eq("Spec Page"), eq("The Models table lists Name, Status."));
    }

    @Test
    void ingestPage_tableWithNoExtractableColumns_getsNoCaptionAtAll() {
        ConfluencePageDetail page = page("sp1", "Spec Page", 2);
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_WITH_DESC);
        when(confluenceClient.fetchPage("sp1")).thenReturn(page);
        // Non-blank content (so it isn't filtered out before tableCaption runs) but its header
        // row has no actual column names to extract -- synthesis has nothing to work with.
        when(parser.parse(anyString())).thenReturn(List.of(
                new ParsedSection("", "|||", ParsedSection.SectionType.TABLE)));
        when(chunkingStrategy.chunk(any(), eq("Spec Page"), anyString()))
                .thenReturn(List.of(new ChunkedContent("table chunk", "TABLE")));
        when(pageRepository.findById("sp1")).thenReturn(Optional.empty());
        when(pageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingestPage("sp1");

        verify(chunkingStrategy).chunk(any(), eq("Spec Page"), eq(""));
    }

    @Test
    void ingestPage_processesPageAndReturnsSinglePageResult() {
        ConfluencePageDetail page = page("sp1", "Spec Page", 2);
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_WITH_DESC);
        when(confluenceClient.fetchPage("sp1")).thenReturn(page);
        when(parser.parse(anyString()))
                .thenReturn(List.of(new ParsedSection("Sec", "Text")));
        when(chunkingStrategy.chunk(any(), eq("Spec Page")))
                .thenReturn(List.of(new ChunkedContent("chunk", "TEXT")));
        when(pageRepository.findById("sp1")).thenReturn(Optional.empty());
        when(pageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IngestionResult result = service.ingestPage("sp1");

        assertThat(result.pagesProcessed()).isEqualTo(1);
        assertThat(result.chunksStored()).isEqualTo(1);
        assertThat(result.pagesSkipped()).isEqualTo(0);
    }

    // ---- helpers ----

    private static ConfluencePageDetail page(String id, String title, int version) {
        return new ConfluencePageDetail(
                id, title,
                new ConfluencePageDetail.Version(version),
                new ConfluencePageDetail.Body(
                        new ConfluencePageDetail.Storage("<p>" + title + " content</p>")),
                new ConfluencePageDetail.Links("/pages/viewpage.action?pageId=" + id));
    }
}
