package com.kssasarma.confluencebot.user;

import com.kssasarma.confluencebot.api.dto.Citation;
import com.kssasarma.confluencebot.api.dto.SourceReference;

import java.util.List;

/**
 * One completed question/answer exchange, persisted as a unit.
 *
 * <p>The pair is written together and only after the answer succeeds, so a failed LLM call never
 * leaves a dangling question in the transcript.
 *
 * <p>The grounding — sources, citations, confidence — is stored with the answer rather than
 * recomputed on read. Retrieval is not deterministic across index updates, so a transcript that
 * re-derived its citations would quietly rewrite history every time a page was re-ingested.
 */
public record ChatTurn(
        String chatId,
        String question,
        String answer,
        List<SourceReference> sources,
        List<String> followUpQuestions,
        List<Citation> citations,
        Double confidence
) {

    public ChatTurn {
        sources = sources == null ? List.of() : List.copyOf(sources);
        followUpQuestions = followUpQuestions == null ? List.of() : List.copyOf(followUpQuestions);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** An exchange recorded without grounding metadata — a no-context answer, or an older caller. */
    public ChatTurn(String chatId, String question, String answer,
                    List<SourceReference> sources, List<String> followUpQuestions) {
        this(chatId, question, answer, sources, followUpQuestions, List.of(), null);
    }
}
