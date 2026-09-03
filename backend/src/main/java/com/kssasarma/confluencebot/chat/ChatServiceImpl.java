package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.api.dto.Citation;
import com.kssasarma.confluencebot.api.dto.SourceReference;
import com.kssasarma.confluencebot.chat.citation.CitationIndex;
import com.kssasarma.confluencebot.chat.confidence.ConfidenceScorer;
import com.kssasarma.confluencebot.chat.confidence.ConfidenceSignals;
import com.kssasarma.confluencebot.chat.prompt.ConfluencePromptBuilder;
import com.kssasarma.confluencebot.chat.source.SourceReferenceFactory;
import com.kssasarma.confluencebot.chat.title.ChatTitleRefiner;
import com.kssasarma.confluencebot.chat.title.TitleRefinementRequest;
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

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Turns a question into a grounded answer: retrieve, prompt, generate, ground, record.
 *
 * <p>The pipeline is identical whether the answer is streamed or returned in one piece; only the
 * generation step differs, which is why both entry points share {@link #prepare} and
 * {@link #ground}.
 *
 * <p>Composition rather than inheritance throughout: retrieval, citation mapping, source
 * presentation, confidence scoring and title summarising are each owned by a collaborator that can
 * be replaced or tested on its own. What is left here is only the order things happen in.
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private static final String LLM_UNAVAILABLE =
            "The AI service is temporarily unavailable. Please try again in a moment.";

    /** A conversation's first turn writes exactly two rows: the question and the answer. */
    private static final long FIRST_TURN_MESSAGE_COUNT = 2L;

    private final HybridSearchService hybridSearchService;
    private final ConfluencePromptBuilder promptBuilder;
    private final LlmGateway llmGateway;
    private final PreferenceService preferenceService;
    private final ChatSessionService chatSessionService;
    private final SourceReferenceFactory sourceReferenceFactory;
    private final ConfidenceScorer confidenceScorer;
    private final ChatTitleRefiner titleRefiner;
    private final double minSimilarityThreshold;

    public ChatServiceImpl(HybridSearchService hybridSearchService,
                           ConfluencePromptBuilder promptBuilder,
                           LlmGateway llmGateway,
                           PreferenceService preferenceService,
                           ChatSessionService chatSessionService,
                           SourceReferenceFactory sourceReferenceFactory,
                           ConfidenceScorer confidenceScorer,
                           ChatTitleRefiner titleRefiner,
                           @Value("${chat.retrieval.min-similarity-threshold:0.4}") double minSimilarityThreshold) {
        this.hybridSearchService = hybridSearchService;
        this.promptBuilder = promptBuilder;
        this.llmGateway = llmGateway;
        this.preferenceService = preferenceService;
        this.chatSessionService = chatSessionService;
        this.sourceReferenceFactory = sourceReferenceFactory;
        this.confidenceScorer = confidenceScorer;
        this.titleRefiner = titleRefiner;
        this.minSimilarityThreshold = minSimilarityThreshold;
    }

    @Override
    public ChatApiResponse chat(ChatQuery query) {
        RetrievalOutcome retrieval = prepare(query);

        if (retrieval.isEmpty()) {
            return record(query, ChatApiResponse.NO_CONTEXT_ANSWER, List.of(), List.of(),
                    List.of(), 0.0).response();
        }

        ParsedAnswer parsed = StreamingAnswerAssembler.parse(llmGateway.complete(retrieval.prompt()));
        Grounding grounding = ground(retrieval, parsed.answer());

        RecordedTurn recorded = record(query, parsed.answer(), retrieval.sources(),
                parsed.followUpQuestions(), grounding.citations(), grounding.confidence());

        // Nothing waits on the summary here: the caller already has its answer, and the better
        // title simply appears the next time the conversation list is read.
        refineTitle(query, parsed.answer(), recorded);

        return recorded.response();
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
            completeQuietly(listener, query, ChatApiResponse.NO_CONTEXT_ANSWER,
                    retrieval, Grounding.none(), List.of());
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
                    completeQuietly(listener, query, parsed.answer(), retrieval,
                            ground(retrieval, parsed.answer()), parsed.followUpQuestions());
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

        ConfidenceSignals signals = retrievalSignals(chunks);
        boolean lowConfidence = signals.topSimilarity() < minSimilarityThreshold;

        if (lowConfidence) {
            log.info("Top similarity {} is below the {} threshold — answering with a confidence caveat",
                    String.format("%.3f", signals.topSimilarity()), minSimilarityThreshold);
        }

        EffectiveChatPreferences preferences = query.user() == null
                ? EffectiveChatPreferences.defaults()
                : preferenceService.resolve(query.user(), query.chatId());

        return new RetrievalOutcome(
                promptBuilder.buildPrompt(query.question(), chunks, lowConfidence, preferences),
                sourceReferenceFactory.from(chunks),
                CitationIndex.fromChunks(chunks),
                signals);
    }

    /**
     * Reads the finished answer back against what it was given.
     *
     * <p>This is the step that separates "retrieval found something" from "the answer used it".
     * An answer that cites nothing scores low however well the search went, which is exactly the
     * case a reader most needs warning about.
     */
    private Grounding ground(RetrievalOutcome retrieval, String answer) {
        if (retrieval.isEmpty()) return Grounding.none();

        CitationIndex index = retrieval.citations();
        ConfidenceSignals signals = retrieval.signals().withCitedMarkers(index.countCitedIn(answer));

        return new Grounding(index.citations(), confidenceScorer.score(signals));
    }

    private static ConfidenceSignals retrievalSignals(List<RetrievedChunk> chunks) {
        List<Double> similarities = chunks.stream().map(RetrievedChunk::getSimilarity).toList();

        Set<String> pages = new HashSet<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk.getPageId() != null && !chunk.getPageId().isBlank()) pages.add(chunk.getPageId());
        }

        return ConfidenceSignals.fromRetrieval(similarities, pages.size());
    }

    /** Persists the exchange (when it belongs to a conversation) and builds the response. */
    private RecordedTurn record(ChatQuery query, String answer, List<SourceReference> sources,
                                List<String> followUps, List<Citation> citations, Double confidence) {
        ChatApiResponse response = new ChatApiResponse(answer, sources, followUps)
                .withGrounding(citations, confidence);
        if (!query.isPersistable()) return new RecordedTurn(response, false);

        ChatSessionResponse session = chatSessionService.recordTurn(query.user(),
                new ChatTurn(query.chatId(), query.question(), answer, sources, followUps,
                        citations, confidence));

        return new RecordedTurn(
                response.inConversation(session.chatId(), session.title()),
                session.messageCount() == FIRST_TURN_MESSAGE_COUNT);
    }

    /**
     * Finishes a stream. The answer already reached the user, so a persistence failure is logged
     * and reported as an un-saved conversation rather than thrown away as an error.
     */
    private void completeQuietly(ChatStreamListener listener, ChatQuery query, String answer,
                                 RetrievalOutcome retrieval, Grounding grounding,
                                 List<String> followUps) {
        RecordedTurn recorded = new RecordedTurn(
                new ChatApiResponse(answer, retrieval.sources(), followUps)
                        .withGrounding(grounding.citations(), grounding.confidence()),
                false);
        try {
            recorded = record(query, answer, retrieval.sources(), followUps,
                    grounding.citations(), grounding.confidence());
        } catch (Exception e) {
            log.error("Could not record the exchange for conversation {}: {}",
                    query.chatId(), e.getMessage(), e);
        }

        // Announced before the answer is closed out, so the transport knows to hold the connection
        // briefly for a title that is still being written.
        ChatApiResponse response = recorded.response();
        listener.expect(refineTitle(query, answer, recorded)
                .thenAccept(title -> title.ifPresent(
                        refined -> listener.onTitleRefined(response.chatId(), refined))));

        listener.onCompleted(response);
    }

    /**
     * Asks for a better conversation name, once, after the exchange that created it.
     *
     * <p>Always returns a future that completes — the refiner swallows its own failures — so no
     * caller has to defend against a naming problem breaking an answer.
     */
    private CompletableFuture<Optional<String>> refineTitle(ChatQuery query, String answer,
                                                            RecordedTurn recorded) {
        return titleRefiner.refine(new TitleRefinementRequest(
                query.user(),
                recorded.response().chatId(),
                query.question(),
                answer,
                recorded.firstTurn()));
    }

    private static void emit(ChatStreamListener listener, String delta) {
        if (delta != null && !delta.isEmpty()) listener.onToken(delta);
    }

    // ── Pipeline value objects ────────────────────────────────────────────────

    /** Everything the model needs, plus what is required to read its answer back. */
    private record RetrievalOutcome(LlmPrompt prompt,
                                    List<SourceReference> sources,
                                    CitationIndex citations,
                                    ConfidenceSignals signals) {

        static RetrievalOutcome empty() {
            return new RetrievalOutcome(null, List.of(), CitationIndex.empty(), ConfidenceSignals.empty());
        }

        boolean isEmpty() {
            return prompt == null;
        }
    }

    /** What the answer turned out to be grounded in. */
    private record Grounding(List<Citation> citations, Double confidence) {

        static Grounding none() {
            return new Grounding(List.of(), 0.0);
        }
    }

    /** The response to return, and whether this exchange is the one that named the conversation. */
    private record RecordedTurn(ChatApiResponse response, boolean firstTurn) {}
}
