package com.kssasarma.confluencebot.rag.service;

import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import com.kssasarma.confluencebot.rag.repository.ChunkSearchRepository;
import com.kssasarma.confluencebot.rag.repository.ChunkSearchRepository.RawCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers only {@code withPinnedContent} — the deterministic override that guarantees a seat for
 * a configured list of pages' table chunks when they're at least plausibly on-topic, gated by
 * real cosine similarity rather than keyword triggers. The rest of the hybrid search pipeline
 * (dense/lexical/fusion/MMR) isn't exercised here: {@link #setUpNaturalResult} stubs it down to a
 * single dummy dense candidate — just enough to clear the "no candidates at all" early return —
 * and stubs {@link ReRankingService#rerank} to hand back an arbitrary "naturally selected" result
 * directly, so each test controls exactly what withPinnedContent sees without a real embedding
 * model or database. {@code toRetrievedChunk} is stubbed per-test to return a fixed similarity,
 * standing in for the real cosine-similarity computation it performs against the query embedding.
 */
@ExtendWith(MockitoExtension.class)
class HybridSearchServiceTest {

    @Mock private ChunkSearchRepository searchRepo;
    @Mock private ReRankingService reRankingService;
    @Mock private EmbeddingModel embeddingModel;

    private HybridSearchService newService(String pinnedPageIds, double similarityFloor) {
        HybridSearchService service = new HybridSearchService(searchRepo, reRankingService, embeddingModel);
        ReflectionTestUtils.setField(service, "topK", 5);
        ReflectionTestUtils.setField(service, "candidatePoolSize", 25);
        ReflectionTestUtils.setField(service, "pinnedPageIds", pinnedPageIds);
        ReflectionTestUtils.setField(service, "pinnedSimilarityFloor", similarityFloor);
        return service;
    }

    private static RetrievedChunk chunk(String id, String pageId) {
        return chunk(id, pageId, 0.5);
    }

    private static RetrievedChunk chunk(String id, String pageId, double similarity) {
        return RetrievedChunk.builder().chunkId(id).pageId(pageId).content(id)
                .title("Some Page").chunkType("TEXT").similarity(similarity).embedding(new float[]{1f}).build();
    }

    /** Stubs everything upstream of withPinnedContent so {@code natural} is what it receives. */
    private void setUpNaturalResult(List<RetrievedChunk> natural) {
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1f});
        RawCandidate dummy = new RawCandidate("dummy", "dummy", "{}", "[1.0]");
        when(searchRepo.findTopNDense(anyString(), anyInt(), any())).thenReturn(List.of(dummy));
        when(searchRepo.findTopNLexical(anyString(), anyInt(), any())).thenReturn(List.of());
        when(searchRepo.toRetrievedChunk(any(), any())).thenReturn(chunk("dummy", "dummy-page"));
        when(reRankingService.rerank(anyString(), any(), anyList(), anyInt())).thenReturn(natural);
    }

    @Test
    void noOpWhenNoPageIsPinned() {
        HybridSearchService service = newService("", 0.3);
        List<RetrievedChunk> natural = List.of(chunk("c1", "page-a"));
        setUpNaturalResult(natural);

        List<RetrievedChunk> result = service.search("some query", null);

        assertThat(result).isEqualTo(natural);
        verify(searchRepo, never()).findTableChunksByPage(anyString());
    }

    @Test
    void pinnedPageChunkIncludedWhenItMeetsTheSimilarityFloor() {
        HybridSearchService service = newService("page-x", 0.3);
        List<RetrievedChunk> natural = List.of(chunk("c1", "page-a"), chunk("c2", "page-b"));
        setUpNaturalResult(natural);

        RawCandidate pinnedRaw = new RawCandidate("pinned-1", "the table", "{}", "[1.0]");
        when(searchRepo.findTableChunksByPage("page-x")).thenReturn(List.of(pinnedRaw));
        when(searchRepo.toRetrievedChunk(eq(pinnedRaw), any()))
                .thenReturn(chunk("pinned-1", "page-x", 0.45));

        List<RetrievedChunk> result = service.search("some query", null);

        assertThat(result).extracting(RetrievedChunk::getChunkId)
                .containsExactly("c1", "c2", "pinned-1");
    }

    @Test
    void pinnedPageChunkExcludedWhenBelowTheSimilarityFloor() {
        // This is the case a keyword-substring trigger could never catch: the query is merely
        // coincidentally wordy in a way that would match a naive trigger, but the pinned page's
        // actual embedding similarity says it isn't really on-topic -- it must stay excluded.
        HybridSearchService service = newService("page-x", 0.3);
        List<RetrievedChunk> natural = List.of(chunk("c1", "page-a"));
        setUpNaturalResult(natural);

        RawCandidate pinnedRaw = new RawCandidate("pinned-1", "the table", "{}", "[1.0]");
        when(searchRepo.findTableChunksByPage("page-x")).thenReturn(List.of(pinnedRaw));
        when(searchRepo.toRetrievedChunk(eq(pinnedRaw), any()))
                .thenReturn(chunk("pinned-1", "page-x", 0.12));

        List<RetrievedChunk> result = service.search("an unrelated question", null);

        assertThat(result).isEqualTo(natural);
    }

    @Test
    void multiplePinnedPagesAreEachEvaluatedIndependently() {
        HybridSearchService service = newService("page-x,page-y", 0.3);
        List<RetrievedChunk> natural = List.of(chunk("c1", "page-a"));
        setUpNaturalResult(natural);

        RawCandidate aboveFloor = new RawCandidate("above-floor", "text", "{}", "[1.0]");
        RawCandidate belowFloor = new RawCandidate("below-floor", "text", "{}", "[1.0]");
        when(searchRepo.findTableChunksByPage("page-x")).thenReturn(List.of(aboveFloor));
        when(searchRepo.findTableChunksByPage("page-y")).thenReturn(List.of(belowFloor));
        when(searchRepo.toRetrievedChunk(eq(aboveFloor), any()))
                .thenReturn(chunk("above-floor", "page-x", 0.5));
        when(searchRepo.toRetrievedChunk(eq(belowFloor), any()))
                .thenReturn(chunk("below-floor", "page-y", 0.1));

        List<RetrievedChunk> result = service.search("some query", null);

        assertThat(result).extracting(RetrievedChunk::getChunkId)
                .containsExactly("c1", "above-floor");
    }

    @Test
    void noDuplicateWhenThePinnedPageAlreadyMadeItNaturally() {
        HybridSearchService service = newService("page-x", 0.3);
        List<RetrievedChunk> natural = List.of(chunk("c1", "page-x"));
        setUpNaturalResult(natural);

        List<RetrievedChunk> result = service.search("some query", null);

        assertThat(result).isEqualTo(natural);
        verify(searchRepo, never()).findTableChunksByPage(anyString());
    }

    @Test
    void noOpWhenThePinnedPageHasNoTableChunks() {
        HybridSearchService service = newService("page-x", 0.3);
        List<RetrievedChunk> natural = List.of(chunk("c1", "page-a"));
        setUpNaturalResult(natural);
        when(searchRepo.findTableChunksByPage("page-x")).thenReturn(List.of());

        List<RetrievedChunk> result = service.search("some query", null);

        assertThat(result).isEqualTo(natural);
    }

    @Test
    void similarityFloorIsConfigurable() {
        HybridSearchService service = newService("page-x", 0.6);
        List<RetrievedChunk> natural = List.of(chunk("c1", "page-a"));
        setUpNaturalResult(natural);

        RawCandidate pinnedRaw = new RawCandidate("pinned-1", "the table", "{}", "[1.0]");
        when(searchRepo.findTableChunksByPage("page-x")).thenReturn(List.of(pinnedRaw));
        // Would have passed the default 0.3 floor, but not this test's stricter 0.6 floor.
        when(searchRepo.toRetrievedChunk(eq(pinnedRaw), any()))
                .thenReturn(chunk("pinned-1", "page-x", 0.45));

        List<RetrievedChunk> result = service.search("some query", null);

        assertThat(result).isEqualTo(natural);
    }
}
