package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.api.dto.SourceReference;
import com.kssasarma.confluencebot.chat.prompt.ConfluencePromptBuilder;
import com.kssasarma.confluencebot.exception.LlmUnavailableException;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import com.kssasarma.confluencebot.rag.service.HybridSearchService;
import com.kssasarma.confluencebot.user.ChatSessionService;
import com.kssasarma.confluencebot.user.ChatTurn;
import com.kssasarma.confluencebot.user.EffectiveChatPreferences;
import com.kssasarma.confluencebot.user.PreferenceService;
import com.kssasarma.confluencebot.user.dto.ChatSessionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Turns a question into a grounded answer: retrieve, prompt, generate, record.
 *
 * The pipeline is identical whether the answer is streamed or returned in one piece; only the
 * generation step differs, which is why both entry points share {@link #prepare}.
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private static final String LLM_UNAVAILABLE =
            "The AI service is temporarily unavailable. Please try again in a moment.";

    private final HybridSearchService hybridSearchService;
    private final ConfluencePromptBuilder promptBuilder;
    private final LlmGateway llmGateway;
    private final PreferenceService preferenceService;
    private final ChatSessionService chatSessionService;
    private final double minSimilarityThreshold;

    public ChatServiceImpl(HybridSearchService hybridSearchService,
                           ConfluencePromptBuilder promptBuilder,
                           LlmGateway llmGateway,
                           PreferenceService preferenceService,
                           ChatSessionService chatSessionService,
                           @Value("${chat.retrieval.min-similarity-threshold:0.4}") double minSimilarityThreshold) {
        this.hybridSearchService = hybridSearchService;
        this.promptBuilder = promptBuilder;
        this.llmGateway = llmGateway;
        this.preferenceService = preferenceService;
        this.chatSessionService = chatSessionService;
        this.minSimilarityThreshold = minSimilarityThreshold;
    }

    @Override
    public ChatApiResponse chat(ChatQuery query) {
        RetrievalOutcome retrieval = prepare(query);

        if (retrieval.isEmpty()) {
            return record(query, ChatApiResponse.NO_CONTEXT_ANSWER, List.of(), List.of());
        }

        ParsedAnswer parsed = StreamingAnswerAssembler.parse(llmGateway.complete(retrieval.prompt()));
        return record(query, parsed.answer(), retrieval.sources(), parsed.followUpQuestions());
    }

    @Override
    public ChatStreamHandle stream(ChatQuery query, ChatStreamListener listener) {
        RetrievalOutcome retrieval;
        try {
            retrieval = prepare(query);
        } catch (Exception e) {
            log.error("Retrieval failed for query '{}': {}", query.question(), e.getMessage(), e);
            listener.onFailed("Could not search the Confluence documentation. Please try again.");
            return ChatStreamHandle.NOOP;
        }

        listener.onSources(retrieval.sources());

        if (retrieval.isEmpty()) {
            listener.onToken(ChatApiResponse.NO_CONTEXT_ANSWER);
            completeQuietly(listener, query, ChatApiResponse.NO_CONTEXT_ANSWER, List.of(), List.of());
            return ChatStreamHandle.NOOP;
        }

        StreamingAnswerAssembler assembler = new StreamingAnswerAssembler();

        Disposable subscription = llmGateway.stream(retrieval.prompt()).subscribe(
                token -> emit(listener, assembler.accept(token)),
                error -> {
                    log.error("Streaming answer failed for query '{}': {}",
                            query.question(), error.getMessage(), error);
                    listener.onFailed(error instanceof LlmUnavailableException
                            ? LLM_UNAVAILABLE
                            : "The answer could not be generated. Please try again.");
                },
                () -> {
                    emit(listener, assembler.remainder());
                    ParsedAnswer parsed = assembler.finish();
                    completeQuietly(listener, query, parsed.answer(),
                            retrieval.sources(), parsed.followUpQuestions());
                });

        return subscription::dispose;
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    /** Retrieval and prompt construction — everything that happens before the model is called. */
    private RetrievalOutcome prepare(ChatQuery query) {
        log.info("Chat query received: {}", query.question());

        List<RetrievedChunk> chunks = hybridSearchService.search(query.question());
        if (chunks.isEmpty()) {
            log.warn("No relevant documents found for query: {}", query.question());
            return RetrievalOutcome.empty();
        }

        double maxSimilarity = chunks.stream()
                .mapToDouble(RetrievedChunk::getSimilarity)
                .max().orElse(0.0);
        boolean lowConfidence = maxSimilarity < minSimilarityThreshold;

        if (lowConfidence) {
            log.info("Top similarity {} is below the {} threshold — answering with a confidence caveat",
                    String.format("%.3f", maxSimilarity), minSimilarityThreshold);
        }

        EffectiveChatPreferences preferences = query.user() == null
                ? EffectiveChatPreferences.defaults()
                : preferenceService.resolve(query.user(), query.chatId());

        return new RetrievalOutcome(
                promptBuilder.buildPrompt(query.question(), chunks, lowConfidence, preferences),
                extractSources(chunks));
    }

    /** Persists the exchange (when it belongs to a conversation) and builds the response. */
    private ChatApiResponse record(ChatQuery query, String answer,
                                   List<SourceReference> sources, List<String> followUps) {
        ChatApiResponse response = new ChatApiResponse(answer, sources, followUps);
        if (!query.isPersistable()) return response;

        ChatSessionResponse session = chatSessionService.recordTurn(query.user(),
                new ChatTurn(query.chatId(), query.question(), answer, sources, followUps));
        return response.inConversation(session.chatId(), session.title());
    }

    /**
     * Finishes a stream. The answer already reached the user, so a persistence failure is logged
     * and reported as an un-saved conversation rather than thrown away as an error.
     */
    private void completeQuietly(ChatStreamListener listener, ChatQuery query, String answer,
                                 List<SourceReference> sources, List<String> followUps) {
        ChatApiResponse response = new ChatApiResponse(answer, sources, followUps);
        try {
            response = record(query, answer, sources, followUps);
        } catch (Exception e) {
            log.error("Could not record the exchange for conversation {}: {}",
                    query.chatId(), e.getMessage(), e);
        }
        listener.onCompleted(response);
    }

    private static void emit(ChatStreamListener listener, String delta) {
        if (delta != null && !delta.isEmpty()) listener.onToken(delta);
    }

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

            String pageUrl = chunk.getPageUrl() != null ? chunk.getPageUrl() : "";
            String heading = chunk.getSectionHeading() != null ? chunk.getSectionHeading() : "";

            byPageId.put(pageId, new SourceReference(
                    pageId,
                    chunk.getTitle(),
                    pageUrl,
                    buildAnchorUrl(pageUrl, heading),
                    chunk.getSpaceKey(),
                    chunk.getSimilarity()));
        }

        return new ArrayList<>(byPageId.values());
    }

    private static String buildAnchorUrl(String pageUrl, String heading) {
        if (pageUrl.isBlank() || heading.isBlank()) return pageUrl;
        return pageUrl + "#" + heading.replace(" ", "-");
    }

    /** Everything the model needs, plus the citations that go with the answer. */
    private record RetrievalOutcome(LlmPrompt prompt, List<SourceReference> sources) {

        static RetrievalOutcome empty() {
            return new RetrievalOutcome(null, List.of());
        }

        boolean isEmpty() {
            return prompt == null;
        }
    }
}
