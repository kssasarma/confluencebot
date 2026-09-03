package com.kssasarma.confluencebot.chat.context;

import com.kssasarma.confluencebot.chat.LlmGateway;
import com.kssasarma.confluencebot.chat.LlmPrompt;
import com.kssasarma.confluencebot.config.ChatContextProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Condenses a follow-up into a standalone retrieval query with a short model call.
 *
 * <p>Three properties are what make this safe to put on the answer path:
 *
 * <ol>
 *   <li><b>It is skipped when it cannot help.</b> A question that already stands on its own is
 *       sent to retrieval untouched, so the common case pays nothing. Only questions that show
 *       some sign of leaning on the conversation reach the model.</li>
 *   <li><b>It is bounded.</b> The call runs on its own small pool under a deadline; when the
 *       deadline passes retrieval proceeds with the question as asked. The feature can therefore
 *       add at most one configured timeout to a question, never an open-ended wait.</li>
 *   <li><b>It never makes retrieval worse.</b> Every failure — a saturated pool, a timeout, an
 *       unavailable model, a reply that does not look like a question — degrades to the original
 *       question, which is exactly what the pipeline searched for before this existed.</li>
 * </ol>
 */
@Component
public class LlmFollowUpQueryRewriter implements FollowUpQueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(LlmFollowUpQueryRewriter.class);

    private static final String SYSTEM_MESSAGE = """
            You rewrite a follow-up question into a standalone search query for a documentation \
            search engine.

            You are given the recent turns of a conversation and the user's latest question. \
            Replace every pronoun and every implicit reference in that question with what it \
            refers to in the conversation, so the result can be understood entirely on its own.

            Rules:
            - Reply with the rewritten question alone: one line, no quotation marks, no preamble, \
            no explanation.
            - Never answer the question. You are rewriting it, not responding to it.
            - Keep the user's own terminology, product names and spelling. Only substitute what is \
            ambiguous on its own.
            - Add nothing the conversation does not already say. If a reference is unclear, leave \
            it as the user wrote it.
            - If the question already stands on its own, reply with it unchanged.""";

    /**
     * Words that point at something said earlier rather than naming it.
     *
     * <p>Deliberately restricted to expressions that genuinely refer back. Weaker signals
     * ("also", "another", "again") appear just as often in perfectly self-contained questions, and
     * including them would buy a model call on nearly every turn for no gain.
     */
    private static final Pattern ANAPHORA = Pattern.compile(
            "\\b(it|its|it's|this|that|these|those|they|them|their|theirs|he|him|his|she|her|hers|"
            + "same|above|former|latter|previous|preceding|earlier|aforementioned)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Openers that continue a thought instead of starting one. */
    private static final Pattern CONTINUATION = Pattern.compile(
            "^(and|but|or|so|then|plus|also|what about|how about|what if|why|why not|ok|okay|yes|no|"
            + "explain|expand|elaborate|clarify|continue|carry on|go on|rephrase|simplify|"
            + "summari[sz]e|compare|more|tell me more|show me more|any (other|others|more))\\b",
            Pattern.CASE_INSENSITIVE);

    /** Labels models add back despite being asked for the question alone. */
    private static final Pattern LABEL_PREFIX = Pattern.compile(
            "^(?:standalone question|rewritten question|rewrite|question|query)\\s*[:\\-–]\\s*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SURROUNDING_QUOTES = Pattern.compile("^[\\s\"'`*]+|[\\s\"'`*]+$");

    /** Below this many words a question is too short to carry its own subject. */
    private static final int SELF_CONTAINED_MIN_WORDS = 6;

    private static final int MIN_REWRITE_LENGTH = 3;

    /**
     * How much of the conversation the rewrite is shown.
     *
     * <p>Far less than the answer itself gets, and deliberately so. Resolving "it" needs the last
     * thing or two that was talked about, not the whole conversation: a reference reaching back
     * six turns is rare, while a long prompt on this call is paid on every follow-up and comes
     * straight out of the deadline. Constants rather than settings — these are properties of the
     * task, not of a deployment.
     */
    private static final int REWRITE_EXCHANGES = 3;
    private static final int REWRITE_ANSWER_CHARS = 400;

    private final LlmGateway llmGateway;
    private final Executor executor;
    private final ChatContextProperties properties;

    public LlmFollowUpQueryRewriter(@Qualifier("contextLlmGateway") LlmGateway llmGateway,
                                    @Qualifier("chatContextExecutor") Executor executor,
                                    ChatContextProperties properties) {
        this.llmGateway = llmGateway;
        this.executor = executor;
        this.properties = properties;
    }

    @Override
    public String rewriteForRetrieval(String question, ConversationContext context) {
        if (question == null || question.isBlank()) return question;
        if (!properties.queryRewritingEnabled()) return question;
        if (context == null || context.isEmpty()) return question;
        if (!dependsOnContext(question)) return question;

        String rewritten = condense(question, context);
        if (rewritten.equals(question)) return question;

        log.info("Rewrote the follow-up '{}' to '{}' for retrieval", question, rewritten);
        return rewritten;
    }

    // ── Gate ──────────────────────────────────────────────────────────────────

    /**
     * Whether the question shows any sign of leaning on what came before.
     *
     * <p>Tuned to over-rewrite rather than under-rewrite. Rewriting a question that did not need
     * it costs one short call and returns the question unchanged; failing to rewrite one that did
     * is the exact defect this class exists to fix, and it is invisible — the user just gets a
     * confidently wrong answer about the wrong page.
     */
    static boolean dependsOnContext(String question) {
        String trimmed = question.strip();
        if (trimmed.isEmpty()) return false;

        if (CONTINUATION.matcher(trimmed).find()) return true;
        if (ANAPHORA.matcher(trimmed).find()) return true;

        return countWords(trimmed) < SELF_CONTAINED_MIN_WORDS;
    }

    private static int countWords(String text) {
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                inWord = false;
            } else if (!inWord) {
                inWord = true;
                words++;
            }
        }
        return words;
    }

    // ── The call ──────────────────────────────────────────────────────────────

    private String condense(String question, ConversationContext context) {
        CompletableFuture<String> work;
        try {
            work = CompletableFuture.supplyAsync(
                    () -> llmGateway.complete(promptFor(question, context)), executor);
        } catch (RejectedExecutionException e) {
            // The pool hands off directly and never queues: no capacity means answer now with the
            // question as asked, rather than make the user wait for a place in a line.
            log.debug("No capacity to rewrite a follow-up; retrieving with the question as asked");
            return question;
        }

        try {
            return sanitise(work.get(properties.rewriteTimeout().toMillis(), TimeUnit.MILLISECONDS),
                    question);
        } catch (TimeoutException e) {
            // The call is left to finish on its own thread and its answer discarded; interrupting
            // it would buy nothing, and the deadline has already been spent waiting.
            log.debug("Rewriting a follow-up exceeded {}; retrieving with the question as asked",
                    properties.rewriteTimeout());
            return question;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return question;
        } catch (Exception e) {
            log.debug("Could not rewrite a follow-up ({}); retrieving with the question as asked",
                    e.getMessage());
            return question;
        }
    }

    private LlmPrompt promptFor(String question, ConversationContext context) {
        ConversationContext recent =
                context.mostRecent(REWRITE_EXCHANGES).withAnswersClippedTo(REWRITE_ANSWER_CHARS);

        String user = "Conversation so far:\n" + recent.transcript()
                + "\n\nFollow-up question: " + question
                + "\n\nStandalone question:";

        // Sent without history of its own: this asks one question *about* a conversation rather
        // than continuing it, and replaying the turns as messages would invite an answer instead
        // of a rewrite.
        return new LlmPrompt(SYSTEM_MESSAGE, user);
    }

    // ── Reading the reply ─────────────────────────────────────────────────────

    /**
     * Accepts a reply only if it still looks like a question the user could have typed.
     *
     * <p>The failure this guards against is not a malformed string, it is a plausible one: a model
     * that answers the question, or explains its rewrite, produces text that would be embedded and
     * searched as though the user had asked it. Falling back to the original question is always
     * safe, so anything doubtful is rejected.
     */
    private String sanitise(String reply, String fallback) {
        if (reply == null || reply.isBlank()) return fallback;

        String candidate = reply.strip().lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("");

        candidate = LABEL_PREFIX.matcher(candidate).replaceFirst("");
        candidate = SURROUNDING_QUOTES.matcher(candidate).replaceAll("");

        if (candidate.length() < MIN_REWRITE_LENGTH) return fallback;
        if (candidate.length() > properties.rewriteMaxChars()) {
            log.debug("Discarded a {}-character rewrite: a standalone question is a question",
                    candidate.length());
            return fallback;
        }

        return candidate;
    }
}
