package com.kssasarma.confluencebot.chat.title;

import com.kssasarma.confluencebot.chat.LlmGateway;
import com.kssasarma.confluencebot.chat.LlmPrompt;
import com.kssasarma.confluencebot.user.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Names a conversation with a cheap model call.
 *
 * <p>Three guarantees hold whatever the model does:
 *
 * <ol>
 *   <li><b>It never blocks the answer.</b> The work runs on its own bounded pool, and the future
 *       handed back completes with {@link Optional#empty()} once the grace period is up whether or
 *       not the model has replied.</li>
 *   <li><b>It never fails a request.</b> Every failure — timeout, circuit breaker, a model that
 *       returns prose instead of a title — degrades to keeping the clipped question.</li>
 *   <li><b>It never overwrites a person.</b> Persistence goes through
 *       {@link ChatSessionService#applyGeneratedTitle}, which declines if the user has renamed the
 *       conversation in the meantime.</li>
 * </ol>
 *
 * <p>A late summary is still persisted even after the caller has given up waiting: the title is
 * then correct on the next page load, which is strictly better than discarding work already paid
 * for.
 */
@Component
public class LlmChatTitleRefiner implements ChatTitleRefiner {

    private static final Logger log = LoggerFactory.getLogger(LlmChatTitleRefiner.class);

    private static final String SYSTEM_MESSAGE = """
            You name conversations. Given a question and its answer, reply with a title of 3 to 6 \
            words describing the topic.

            Reply with the title alone. No quotation marks, no trailing punctuation, no prefix such \
            as "Title:", no explanation. Use sentence case. If the exchange has no clear topic, \
            reply with the single word NONE.""";

    /** Long enough to establish the topic; short enough to keep the call cheap. */
    private static final int MAX_EXCERPT = 600;

    private static final int MAX_TITLE_WORDS = 8;
    private static final int MAX_TITLE_LENGTH = 60;
    private static final int MIN_TITLE_LENGTH = 3;

    /** Wrapping and labelling models add back despite being asked not to. */
    private static final Pattern SURROUNDING_NOISE =
            Pattern.compile("^[\\s\"'`*_#]+|[\\s\"'`*_.:;,!]+$");
    private static final Pattern LABEL_PREFIX =
            Pattern.compile("^(?:title|conversation title|topic)\\s*[:\\-–]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final LlmGateway llmGateway;
    private final ChatSessionService chatSessionService;
    private final Executor executor;
    private final boolean enabled;
    private final Duration timeout;

    public LlmChatTitleRefiner(LlmGateway llmGateway,
                               ChatSessionService chatSessionService,
                               @Qualifier("chatTitleExecutor") Executor executor,
                               @Value("${chat.title.enabled:true}") boolean enabled,
                               @Value("${chat.title.timeout:PT4S}") Duration timeout) {
        this.llmGateway = llmGateway;
        this.chatSessionService = chatSessionService;
        this.executor = executor;
        this.enabled = enabled;
        this.timeout = timeout;
    }

    @Override
    public CompletableFuture<Optional<String>> refine(TitleRefinementRequest request) {
        if (!enabled || request == null || !request.isRefinable()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        CompletableFuture<Optional<String>> work = CompletableFuture
                .supplyAsync(() -> summariseAndPersist(request), executor)
                .exceptionally(error -> {
                    log.debug("Could not summarise a title for conversation {}: {}",
                            request.chatId(), error.getMessage());
                    return Optional.empty();
                });

        // The upstream call keeps running past the deadline so a slow summary is still saved for
        // the next page load; only the caller's view of it gives up.
        return work.completeOnTimeout(Optional.empty(), timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private Optional<String> summariseAndPersist(TitleRefinementRequest request) {
        Optional<String> title = sanitise(llmGateway.complete(promptFor(request)));
        if (title.isEmpty()) return Optional.empty();

        boolean applied = chatSessionService.applyGeneratedTitle(
                request.user(), request.chatId(), title.get());

        return applied ? title : Optional.empty();
    }

    private LlmPrompt promptFor(TitleRefinementRequest request) {
        String user = "Question: " + excerpt(request.question())
                + "\n\nAnswer: " + excerpt(request.answer());
        return new LlmPrompt(SYSTEM_MESSAGE, user);
    }

    private static String excerpt(String text) {
        if (text == null) return "";
        String flattened = WHITESPACE.matcher(text).replaceAll(" ").strip();
        return flattened.length() <= MAX_EXCERPT ? flattened : flattened.substring(0, MAX_EXCERPT);
    }

    /**
     * Accepts a model reply only if it actually looks like a title.
     *
     * <p>A model that answers the question instead of naming it, apologises, or returns a
     * paragraph must not end up in the sidebar — the clipped question is a better outcome than a
     * wrong one, so anything unexpected is rejected rather than trimmed into shape.
     */
    static Optional<String> sanitise(String raw) {
        if (raw == null) return Optional.empty();

        // First line first, then collapse: doing it the other way round folds an explanatory
        // second line into the title instead of discarding it.
        String candidate = raw.lines().findFirst().orElse("");
        candidate = WHITESPACE.matcher(candidate).replaceAll(" ").strip();
        candidate = LABEL_PREFIX.matcher(candidate).replaceFirst("");
        candidate = SURROUNDING_NOISE.matcher(candidate).replaceAll("");

        if (candidate.isBlank()) return Optional.empty();
        if (candidate.length() < MIN_TITLE_LENGTH || candidate.length() > MAX_TITLE_LENGTH) {
            return Optional.empty();
        }
        if ("none".equals(candidate.toLowerCase(Locale.ROOT))) return Optional.empty();
        if (candidate.split(" ").length > MAX_TITLE_WORDS) return Optional.empty();

        return Optional.of(candidate);
    }
}
