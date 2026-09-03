package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.confluence.ConfluenceClient;
import com.kssasarma.confluencebot.confluence.dto.ConfluencePageDetail;
import com.kssasarma.confluencebot.confluence.dto.SpaceMetadata;
import com.kssasarma.confluencebot.confluence.parser.ParsedSection;
import com.kssasarma.confluencebot.confluence.parser.StorageFormatParser;
import com.kssasarma.confluencebot.config.ConfluenceProperties;
import com.kssasarma.confluencebot.ingestion.chunking.SemanticChunkingStrategy;
import com.kssasarma.confluencebot.ingestion.chunking.SemanticChunkingStrategy.ChunkedContent;
import com.kssasarma.confluencebot.repository.ConfluencePageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of("stale1"));

        service.ingestSpace("ENG");

        verify(jdbcTemplate).update(anyString(), eq("stale1"));
        verify(pageRepository).deleteById("stale1");
    }

    @Test
    void ingestSpace_forcedRun_stillRemovesPageNoLongerInConfluence() {
        when(confluenceClient.fetchSpaceMetadata("ENG")).thenReturn(SPACE_WITH_DESC);
        when(confluenceClient.fetchAllPages("ENG")).thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of("stale2"));

        service.ingestSpace("ENG", true);

        verify(pageRepository).deleteById("stale2");
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
