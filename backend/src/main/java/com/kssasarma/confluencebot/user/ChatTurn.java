package com.kssasarma.confluencebot.user;

import com.kssasarma.confluencebot.api.dto.SourceReference;

import java.util.List;

/**
 * One completed question/answer exchange, persisted as a unit.
 *
 * The pair is written together and only after the answer succeeds, so a failed LLM call never
 * leaves a dangling question in the transcript.
 */
public record ChatTurn(
        String chatId,
        String question,
        String answer,
        List<SourceReference> sources,
        List<String> followUpQuestions
) {}
