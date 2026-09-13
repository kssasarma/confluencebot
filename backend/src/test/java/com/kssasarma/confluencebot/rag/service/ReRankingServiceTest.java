package com.kssasarma.confluencebot.rag.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import com.kssasarma.confluencebot.config.ChatRerankProperties;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReRankingServiceTest {

    private ReRankingService newService(boolean llmRerankEnabled, boolean guaranteeTableChunk) {
        return newService(llmRerankEnabled, guaranteeTableChunk, 2);
    }

    private ReRankingService newService(boolean llmRerankEnabled, boolean guaranteeTableChunk,
                                         int guaranteeTableChunkCount) {
        ChatRerankProperties props = new ChatRerankProperties(
                llmRerankEnabled, ChatRerankProperties.Transport.NATIVE,
                "", "", "", 0.0, 64);
        ReRankingService service = new ReRankingService(
                (query, documents) -> List.of(),
                props,
                CircuitBreaker.ofDefaults("test-rerank"),
                Bulkhead.ofDefaults("test-rerank"));
        ReflectionTestUtils.setField(service, "mmrLambda", 0.7);
        ReflectionTestUtils.setField(service, "fusionWeight", 0.5);
        ReflectionTestUtils.setField(service, "guaranteeTableChunk", guaranteeTableChunk);
        ReflectionTestUtils.setField(service, "guaranteeTableChunkCount", guaranteeTableChunkCount);
        return service;
    }

    private static ReRankingService.ScoredCandidate candidate(String id, String chunkType,
                                                                float[] embedding, double fusionScore) {
        RetrievedChunk chunk = RetrievedChunk.builder()
                .chunkId(id)
                .content(id)
                .title("Widget Catalog")
                .chunkType(chunkType)
                .embedding(embedding)
                .build();
        return new ReRankingService.ScoredCandidate(chunk, embedding, fusionScore);
    }

    /**
     * The table chunk's embedding is deliberately the poorest match to the query — this is the
     * realistic case (bare cell text vs. fluent prose) that lets three unrelated prose chunks
     * outscore it on relevance and fill every MMR slot ahead of it.
     */
    @Test
    void mmrCanDropTheOnlyTableChunkWithoutTheFloor() {
        ReRankingService service = newService(false, false);
        float[] query = {1f, 0f};

        List<ReRankingService.ScoredCandidate> candidates = List.of(
                candidate("prose-1", "TEXT", new float[]{0.99f, 0.1f}, 0.9),
                candidate("prose-2", "TEXT", new float[]{0.95f, 0.2f}, 0.8),
                candidate("prose-3", "TEXT", new float[]{0.9f, 0.3f}, 0.7),
                candidate("table-1", "TABLE", new float[]{0.4f, 0.9f}, 0.6));

        List<RetrievedChunk> result = service.rerank("what models are supported", query, candidates, 3);

        assertThat(result).extracting(RetrievedChunk::getChunkId)
                .doesNotContain("table-1");
    }

    @Test
    void tableFloorGuaranteesTheTableChunkSurvives() {
        ReRankingService service = newService(false, true);
        float[] query = {1f, 0f};

        List<ReRankingService.ScoredCandidate> candidates = List.of(
                candidate("prose-1", "TEXT", new float[]{0.99f, 0.1f}, 0.9),
                candidate("prose-2", "TEXT", new float[]{0.95f, 0.2f}, 0.8),
                candidate("prose-3", "TEXT", new float[]{0.9f, 0.3f}, 0.7),
                candidate("table-1", "TABLE", new float[]{0.4f, 0.9f}, 0.6));

        List<RetrievedChunk> result = service.rerank("what models are supported", query, candidates, 3);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(RetrievedChunk::getChunkId).contains("table-1");
    }

    @Test
    void tableFloorIsANoOpWhenMmrAlreadyKeptATableChunk() {
        ReRankingService service = newService(false, true);
        float[] query = {1f, 0f};

        List<ReRankingService.ScoredCandidate> candidates = List.of(
                candidate("table-1", "TABLE", new float[]{0.99f, 0.1f}, 0.9),
                candidate("prose-1", "TEXT", new float[]{0.95f, 0.2f}, 0.8));

        List<RetrievedChunk> result = service.rerank("what models are supported", query, candidates, 2);

        assertThat(result).extracting(RetrievedChunk::getChunkId)
                .containsExactlyInAnyOrder("table-1", "prose-1");
    }

    @Test
    void tableFloorIsANoOpWhenPoolHasNoTableChunk() {
        ReRankingService service = newService(false, true);
        float[] query = {1f, 0f};

        List<ReRankingService.ScoredCandidate> candidates = List.of(
                candidate("prose-1", "TEXT", new float[]{0.99f, 0.1f}, 0.9),
                candidate("prose-2", "TEXT", new float[]{0.95f, 0.2f}, 0.8));

        List<RetrievedChunk> result = service.rerank("what models are supported", query, candidates, 1);

        assertThat(result).extracting(RetrievedChunk::getChunkId).containsExactly("prose-1");
    }

    /**
     * The bug found in production: two distinct, unrelated tables both existed in the same corpus.
     * MMR naturally kept the weaker one (table-b, a sandbox page) because its embedding happened to
     * read closer to the query; the old single-slot floor saw "a table is present" and stopped
     * looking, so the actually-relevant table (table-a, a much stronger fusion match) was silently
     * dropped even though it sat right there in the candidate pool. The fix ranks every table
     * candidate by fusion score and guarantees the top N seats regardless of what MMR already kept.
     */
    @Test
    void aHigherScoringTableReplacesALowerScoringOneMmrAlreadyKept() {
        ReRankingService service = newService(false, true, 1);
        float[] query = {1f, 0f};

        List<ReRankingService.ScoredCandidate> candidates = List.of(
                candidate("prose-1", "TEXT", new float[]{0.99f, 0.1f}, 0.9),
                // table-b reads closer to the query embedding, so MMR keeps it over table-a —
                // but table-a is the stronger candidate by fusion score (dense + lexical agreement).
                candidate("table-b", "TABLE", new float[]{0.9f, 0.3f}, 0.5),
                candidate("table-a", "TABLE", new float[]{0.2f, 0.9f}, 0.95));

        List<RetrievedChunk> result = service.rerank("what models are supported", query, candidates, 2);

        assertThat(result).extracting(RetrievedChunk::getChunkId)
                .contains("table-a")
                .doesNotContain("table-b");
    }

    @Test
    void multipleDistinctTablesEachGetAGuaranteedSeatUpToTheConfiguredCount() {
        ReRankingService service = newService(false, true, 2);
        float[] query = {1f, 0f};

        List<ReRankingService.ScoredCandidate> candidates = List.of(
                candidate("prose-1", "TEXT", new float[]{0.99f, 0.1f}, 0.9),
                candidate("prose-2", "TEXT", new float[]{0.97f, 0.15f}, 0.85),
                candidate("prose-3", "TEXT", new float[]{0.95f, 0.2f}, 0.8),
                candidate("table-a", "TABLE", new float[]{0.3f, 0.9f}, 0.7),
                candidate("table-b", "TABLE", new float[]{0.2f, 0.95f}, 0.6));

        List<RetrievedChunk> result = service.rerank("what models are supported", query, candidates, 3);

        assertThat(result).extracting(RetrievedChunk::getChunkId)
                .contains("table-a", "table-b");
    }

    @Test
    void guaranteeTableChunkCountZero_disablesTheFloorEntirely() {
        ReRankingService service = newService(false, true, 0);
        float[] query = {1f, 0f};

        List<ReRankingService.ScoredCandidate> candidates = List.of(
                candidate("prose-1", "TEXT", new float[]{0.99f, 0.1f}, 0.9),
                candidate("prose-2", "TEXT", new float[]{0.95f, 0.2f}, 0.8),
                candidate("prose-3", "TEXT", new float[]{0.9f, 0.3f}, 0.7),
                candidate("table-1", "TABLE", new float[]{0.4f, 0.9f}, 0.6));

        List<RetrievedChunk> result = service.rerank("what models are supported", query, candidates, 3);

        assertThat(result).extracting(RetrievedChunk::getChunkId).doesNotContain("table-1");
    }
}
