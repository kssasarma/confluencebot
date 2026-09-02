package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.api.dto.SourceReference;
import com.kssasarma.confluencebot.chat.prompt.ConfluencePromptBuilder;
import com.kssasarma.confluencebot.config.ChatRetrievalProperties;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import com.kssasarma.confluencebot.rag.service.HybridSearchService;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private static final String FOLLOW_UP_MARKER = "---FOLLOW-UP-QUESTIONS---";
    private static final String LLM_UNAVAILABLE =
        "The AI service is temporarily unavailable. Please try again in a moment.";

    private final ChatClient chatClient;
    private final HybridSearchService hybridSearchService;
    private final ConfluencePromptBuilder promptBuilder;
    private final ChatRetrievalProperties retrievalProps;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    @Value("${chat.retrieval.min-similarity-threshold:0.4}")
    private double minSimilarityThreshold;

    public ChatServiceImpl(
            ChatClient.Builder chatClientBuilder,
            HybridSearchService hybridSearchService,
            ConfluencePromptBuilder promptBuilder,
            ChatRetrievalProperties retrievalProps,
            @Qualifier("llmCircuitBreaker") CircuitBreaker circuitBreaker,
            @Qualifier("llmBulkhead") Bulkhead bulkhead) {
        this.chatClient          = chatClientBuilder.build();
        this.hybridSearchService = hybridSearchService;
        this.promptBuilder       = promptBuilder;
        this.retrievalProps      = retrievalProps;
        this.circuitBreaker      = circuitBreaker;
        this.bulkhead            = bulkhead;
    }

    @Override
    public ChatApiResponse chat(String query) {
        log.info("Chat query received: {}", query);

        List<RetrievedChunk> chunks = hybridSearchService.search(query);

        if (chunks.isEmpty()) {
            log.warn("No relevant documents found for query: {}", query);
            return ChatApiResponse.noContext();
        }

        double maxSimilarity = chunks.stream()
            .mapToDouble(RetrievedChunk::getSimilarity)
            .max().orElse(0.0);
        boolean lowConfidence = maxSimilarity < minSimilarityThreshold;

        if (lowConfidence) {
            log.info("Max similarity {:.3f} below threshold {} — answering with confidence caveat",
                maxSimilarity, minSimilarityThreshold);
        }

        String prompt = promptBuilder.buildPrompt(query, chunks, lowConfidence);
        String rawAnswer = callLlmWithResilience(prompt, query);

        ParsedAnswer parsed = parseAnswer(rawAnswer);
        List<SourceReference> sources = extractSources(chunks);

        return new ChatApiResponse(parsed.answer(), sources, parsed.followUpQuestions());
    }

    // ── LLM call with circuit-breaker + bulkhead ──────────────────────────────

    private String callLlmWithResilience(String prompt, String query) {
        int maxAttempts = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return bulkhead.executeSupplier(
                    () -> circuitBreaker.executeSupplier(
                        () -> chatClient.prompt().user(prompt).call().content()
                    )
                );
            } catch (CallNotPermittedException e) {
                log.error("LLM circuit breaker OPEN for query: {}", query);
                return LLM_UNAVAILABLE;
            } catch (BulkheadFullException e) {
                log.error("LLM bulkhead full for query: {}", query);
                return LLM_UNAVAILABLE;
            } catch (Exception e) {
                lastException = e;
                log.warn("LLM call attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) sleepBackoff(attempt);
            }
        }

        log.error("All LLM attempts failed for query: {}", query, lastException);
        return LLM_UNAVAILABLE;
    }

    // ── Answer parsing ────────────────────────────────────────────────────────

    private ParsedAnswer parseAnswer(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return new ParsedAnswer(LLM_UNAVAILABLE, Collections.emptyList());
        }

        int markerIdx = rawAnswer.indexOf(FOLLOW_UP_MARKER);
        if (markerIdx == -1) {
            return new ParsedAnswer(rawAnswer.strip(), Collections.emptyList());
        }

        String answer       = rawAnswer.substring(0, markerIdx).strip();
        String followUpSection = rawAnswer.substring(markerIdx + FOLLOW_UP_MARKER.length()).strip();

        List<String> followUps = Arrays.stream(followUpSection.split("\n"))
            .map(String::strip)
            .filter(line -> !line.isBlank())
            .limit(3)
            .toList();

        return new ParsedAnswer(answer, followUps);
    }

    private record ParsedAnswer(String answer, List<String> followUpQuestions) {}

    // ── Source extraction ─────────────────────────────────────────────────────

    /**
     * Builds one SourceReference per unique page from the retrieved chunks.
     * Chunks arrive in re-ranked order (most relevant first), so the first chunk seen
     * for each page_id is the best match — subsequent chunks for the same page are skipped.
     */
    private List<SourceReference> extractSources(List<RetrievedChunk> chunks) {
        LinkedHashMap<String, SourceReference> byPageId = new LinkedHashMap<>();

        for (RetrievedChunk chunk : chunks) {
            String pageId = chunk.getPageId();
            if (pageId == null || pageId.isBlank() || byPageId.containsKey(pageId)) continue;

            String pageUrl  = chunk.getPageUrl() != null ? chunk.getPageUrl() : "";
            String heading  = chunk.getSectionHeading() != null ? chunk.getSectionHeading() : "";
            String anchorUrl = buildAnchorUrl(pageUrl, heading);

            byPageId.put(pageId, new SourceReference(
                pageId,
                chunk.getTitle(),
                pageUrl,
                anchorUrl,
                chunk.getSpaceKey(),
                chunk.getSimilarity()
            ));
        }

        return new ArrayList<>(byPageId.values());
    }

    private static String buildAnchorUrl(String pageUrl, String heading) {
        if (pageUrl.isBlank() || heading.isBlank()) return pageUrl;
        return pageUrl + "#" + heading.replace(" ", "-");
    }

    private static void sleepBackoff(int attempt) {
        try { Thread.sleep(500L * (1L << (attempt - 1))); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
